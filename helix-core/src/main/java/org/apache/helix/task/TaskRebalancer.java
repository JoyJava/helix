package org.apache.helix.task;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.Date;
import java.util.Map;
import java.util.Set;

import org.apache.helix.HelixDefinedState;
import org.apache.helix.HelixManager;
import org.apache.helix.controller.rebalancer.Rebalancer;
import org.apache.helix.controller.rebalancer.internal.MappingCalculator;
import org.apache.helix.controller.rebalancer.util.RebalanceScheduler;
import org.apache.helix.controller.stages.ClusterDataCache;
import org.apache.helix.controller.stages.CurrentStateOutput;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.Partition;
import org.apache.helix.model.Resource;
import org.apache.helix.model.ResourceAssignment;
import org.apache.helix.monitoring.mbeans.ClusterStatusMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

/**
 * Abstract rebalancer class for the {@code Task} state model.
 */
public abstract class TaskRebalancer extends AbstractTaskDispatcher
    implements Rebalancer, MappingCalculator {
  private static final Logger LOG = LoggerFactory.getLogger(TaskRebalancer.class);

  @Override
  public void init(HelixManager manager) {
    _manager = manager;
  }

  @Override
  public abstract ResourceAssignment computeBestPossiblePartitionState(ClusterDataCache clusterData,
      IdealState taskIs, Resource resource, CurrentStateOutput currStateOutput);

  /**
   * Checks if the workflow has been stopped.
   * @param ctx Workflow context containing task states
   * @param cfg Workflow config containing set of tasks
   * @return returns true if all tasks are {@link TaskState#STOPPED}, false otherwise.
   */
  protected boolean isWorkflowStopped(WorkflowContext ctx, WorkflowConfig cfg) {
    for (String job : cfg.getJobDag().getAllNodes()) {
      TaskState jobState = ctx.getJobState(job);
      if (jobState != null
          && (jobState.equals(TaskState.IN_PROGRESS) || jobState.equals(TaskState.STOPPING))) {
        return false;
      }
    }
    return true;
  }

  protected ResourceAssignment buildEmptyAssignment(String name,
      CurrentStateOutput currStateOutput) {
    ResourceAssignment assignment = new ResourceAssignment(name);
    Set<Partition> partitions = currStateOutput.getCurrentStateMappedPartitions(name);
    for (Partition partition : partitions) {
      Map<String, String> currentStateMap = currStateOutput.getCurrentStateMap(name, partition);
      Map<String, String> replicaMap = Maps.newHashMap();
      for (String instanceName : currentStateMap.keySet()) {
        replicaMap.put(instanceName, HelixDefinedState.DROPPED.toString());
      }
      assignment.addReplicaMap(partition, replicaMap);
    }
    return assignment;
  }

  /**
   * Check all the dependencies of a job to determine whether the job is ready to be scheduled.
   * @param job
   * @param workflowCfg
   * @param workflowCtx
   * @return
   */
  protected boolean isJobReadyToSchedule(String job, WorkflowConfig workflowCfg,
      WorkflowContext workflowCtx, int incompleteAllCount, Map<String, JobConfig> jobConfigMap,
      ClusterDataCache clusterDataCache) {
    int notStartedCount = 0;
    int failedOrTimeoutCount = 0;
    int incompleteParentCount = 0;

    for (String parent : workflowCfg.getJobDag().getDirectParents(job)) {
      TaskState jobState = workflowCtx.getJobState(parent);
      if (jobState == null || jobState == TaskState.NOT_STARTED) {
        ++notStartedCount;
      } else if (jobState == TaskState.FAILED || jobState == TaskState.TIMED_OUT) {
        ++failedOrTimeoutCount;
      } else if (jobState != TaskState.COMPLETED) {
        incompleteParentCount++;
      }
    }

    // If there is any parent job not started, this job should not be scheduled
    if (notStartedCount > 0) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(String.format("Job %s is not ready to start, notStartedParent(s)=%d.", job,
            notStartedCount));
      }
      return false;
    }

    // If there is parent job failed, schedule the job only when ignore dependent
    // job failure enabled
    JobConfig jobConfig = jobConfigMap.get(job);
    if (jobConfig == null) {
      LOG.error(String.format("The job config is missing for job %s", job));
      return false;
    }
    if (failedOrTimeoutCount > 0 && !jobConfig.isIgnoreDependentJobFailure()) {
      markJobFailed(job, null, workflowCfg, workflowCtx, jobConfigMap, clusterDataCache);
      if (LOG.isDebugEnabled()) {
        LOG.debug(String.format("Job %s is not ready to start, failedCount(s)=%d.", job,
            failedOrTimeoutCount));
      }
      return false;
    }

    if (workflowCfg.isJobQueue()) {
      // If job comes from a JobQueue, it should apply the parallel job logics
      if (incompleteAllCount >= workflowCfg.getParallelJobs()) {
        if (LOG.isDebugEnabled()) {
          LOG.debug(String.format("Job %s is not ready to schedule, inCompleteJobs(s)=%d.", job,
              incompleteAllCount));
        }
        return false;
      }
    } else {
      // If this job comes from a generic workflow, job will not be scheduled until
      // all the direct parent jobs finished
      if (incompleteParentCount > 0) {
        if (LOG.isDebugEnabled()) {
          LOG.debug(String.format("Job %s is not ready to start, notFinishedParent(s)=%d.", job,
              incompleteParentCount));
        }
        return false;
      }
    }

    return true;
  }

  /**
   * Check if a workflow is ready to schedule.
   * @param workflowCfg the workflow to check
   * @return true if the workflow is ready for schedule, false if not ready
   */
  protected boolean isWorkflowReadyForSchedule(WorkflowConfig workflowCfg) {
    Date startTime = workflowCfg.getStartTime();
    // Workflow with non-scheduled config or passed start time is ready to schedule.
    return (startTime == null || startTime.getTime() <= System.currentTimeMillis());
  }

  @Override
  public IdealState computeNewIdealState(String resourceName, IdealState currentIdealState,
      CurrentStateOutput currentStateOutput, ClusterDataCache clusterData) {
    // All of the heavy lifting is in the ResourceAssignment computation,
    // so this part can just be a no-op.
    return currentIdealState;
  }
}