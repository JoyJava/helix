package com.linkedin.clustermanager.messaging;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.josql.Query;
import org.josql.QueryExecutionException;
import org.josql.QueryParseException;
import org.josql.QueryResults;

import com.linkedin.clustermanager.ClusterDataAccessor;
import com.linkedin.clustermanager.ClusterManager;
import com.linkedin.clustermanager.Criteria;
import com.linkedin.clustermanager.Criteria.DataSource;
import com.linkedin.clustermanager.PropertyType;
import com.linkedin.clustermanager.ZNRecord;
import com.linkedin.clustermanager.josql.ClusterJosqlQueryProcessor;
import com.linkedin.clustermanager.josql.ZNRecordRow;

public class CriteriaEvaluator
{
  private static Logger logger = Logger.getLogger(CriteriaEvaluator.class);
  
  public List<Map<String, String>> evaluateCriteria(Criteria recipientCriteria, ClusterManager manager)
  {
    List<Map<String, String>> selected = new ArrayList<Map<String, String>>();
    String resourceGroup = recipientCriteria.getResourceGroup();
    ClusterDataAccessor accessor = manager.getDataAccessor();     
    // ClusterJosqlQueryProcessor is per resourceGroup
    List<String> resourceGroups = new ArrayList<String>();
    
    // Find out the resource groups that we need to process
    // If the resource group is not specified, we will try all resource groups
    List<ZNRecord> idealStates = accessor.getChildValues(PropertyType.IDEALSTATES);
    if(resourceGroup.equals(""))
    {
      resourceGroup = "%";
    }
    String sqlForResourceGroups = "SELECT id FROM com.linkedin.clustermanager.ZNRecord WHERE id LIKE '" + resourceGroup+"'";
    Query q = new Query();
    try
    {
      q.parse(sqlForResourceGroups);
      QueryResults qr = q.execute(idealStates);
      for(Object o : qr.getResults())
      {
        resourceGroups.add(((List<String>)o).get(0));
      }
    } 
    catch (Exception e)
    {
      logger.error("", e);
      return selected;
    } 
    
    for(String resource : resourceGroups)
    {
      logger.info("Checking resourceGroup " + resource);

      String queryFields = 
          (!recipientCriteria.getInstanceName().equals("")  ? " " + ZNRecordRow.MAP_SUBKEY  : " ''") +","+
          (!recipientCriteria.getResourceGroup().equals("") ? " " + ZNRecordRow.ZNRECORD_ID : " ''") +","+
          (!recipientCriteria.getResourceKey().equals("")   ? " " + ZNRecordRow.MAP_KEY   : " ''") +","+
          (!recipientCriteria.getResourceState().equals("") ? " " + ZNRecordRow.MAP_VALUE : " '' ");
      
      String matchCondition = 
          ZNRecordRow.MAP_SUBKEY   + " LIKE '" + (!recipientCriteria.getInstanceName().equals("") ? (recipientCriteria.getInstanceName() +"'") :   "%' ") + " AND "+
          ZNRecordRow.ZNRECORD_ID+ " LIKE '" + (!recipientCriteria.getResourceGroup().equals("") ? (recipientCriteria.getResourceGroup() +"'") : "%' ") + " AND "+
          ZNRecordRow.MAP_KEY   + " LIKE '" + (!recipientCriteria.getResourceKey().equals("")   ? (recipientCriteria.getResourceKey()  +"'") :  "%' ") + " AND "+
          ZNRecordRow.MAP_VALUE  + " LIKE '" + (!recipientCriteria.getResourceState().equals("") ? (recipientCriteria.getResourceState()+"'") :  "%' ") + " AND "+
          ZNRecordRow.MAP_SUBKEY   + " IN ((SELECT [*]id FROM :LIVEINSTANCES))";
          
      
      String queryTarget = recipientCriteria.getDataSource().toString() + ClusterJosqlQueryProcessor.FLATTABLE;
      
      String josql = "SELECT DISTINCT " + queryFields
                   + " FROM " + queryTarget + " WHERE "
                   + matchCondition;
      ClusterJosqlQueryProcessor p = new ClusterJosqlQueryProcessor(manager);
      List<Object> result = new ArrayList<Object>();
      try
      {
        result = p.runJoSqlQuery(josql, resource, null);
      } 
      catch (Exception e)
      {
        logger.error("", e);
        return selected;
      } 
      
      for(Object o : result)
      {
        Map<String, String> resultRow = new HashMap<String, String>();
        List<Object> row = (List<Object>)o;
        resultRow.put("instanceName", (String)(row.get(0)));
        resultRow.put("resourceGroup", (String)(row.get(1)));
        resultRow.put("resourceKey", (String)(row.get(2)));
        resultRow.put("resourceState", (String)(row.get(3)));
        selected.add(resultRow);
      }
    }
    return selected;
  }
}
