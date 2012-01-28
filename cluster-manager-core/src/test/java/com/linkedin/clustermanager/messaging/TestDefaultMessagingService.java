package com.linkedin.clustermanager.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.linkedin.clustermanager.ClusterDataAccessor;
import com.linkedin.clustermanager.ClusterManager;
import com.linkedin.clustermanager.Criteria;
import com.linkedin.clustermanager.InstanceType;
import com.linkedin.clustermanager.Mocks;
import com.linkedin.clustermanager.NotificationContext;
import com.linkedin.clustermanager.PropertyPathConfig;
import com.linkedin.clustermanager.PropertyType;
import com.linkedin.clustermanager.ZNRecord;
import com.linkedin.clustermanager.messaging.handling.CMTaskResult;
import com.linkedin.clustermanager.messaging.handling.MessageHandler;
import com.linkedin.clustermanager.messaging.handling.MessageHandler.ErrorCode;
import com.linkedin.clustermanager.messaging.handling.MessageHandler.ErrorType;
import com.linkedin.clustermanager.messaging.handling.MessageHandlerFactory;
import com.linkedin.clustermanager.model.LiveInstance.LiveInstanceProperty;
import com.linkedin.clustermanager.model.Message;
import com.linkedin.clustermanager.tools.IdealStateCalculatorForStorageNode;

public class TestDefaultMessagingService
{
  class MockClusterManager extends Mocks.MockManager
  {
    class MockDataAccessor extends Mocks.MockAccessor
    {
      @Override
      public ZNRecord getProperty(PropertyType type, String... keys)
      {
        if(type == PropertyType.EXTERNALVIEW || type == PropertyType.IDEALSTATES)
        {
          return _externalView;
        }
        return null;
      }

      @Override
      public List<ZNRecord> getChildValues(PropertyType type, String... keys)
//      public <T extends ZNRecordDecorator> List<T> getChildValues(Class<T> clazz, PropertyType type,
//                                                                  String... keys)
      {
        List<ZNRecord> result = new ArrayList<ZNRecord>();
//        List<T> result = new ArrayList<T>();

        if(type == PropertyType.EXTERNALVIEW || type == PropertyType.IDEALSTATES)
        {
//          result.add(ZNRecordDecorator.convertInstance(clazz, _externalView));
          result.add(_externalView);
          return result;
        }
        else if(type == PropertyType.LIVEINSTANCES)
        {
//          return ZNRecordDecorator.convertList(clazz, _liveInstances);
          return _liveInstances;
        }

        return result;
      }
    }

    ClusterDataAccessor _accessor = new MockDataAccessor();
    ZNRecord _externalView;
    List<String> _instances;
    List<ZNRecord> _liveInstances;
    String _db = "DB";
    int _replicas = 3;
    int _partitions = 50;

    public MockClusterManager()
    {
      _liveInstances = new ArrayList<ZNRecord>();
      _instances = new ArrayList<String>();
      for(int i = 0;i<5; i++)
      {
        String instance = "localhost_"+(12918+i);
        _instances.add(instance);
        ZNRecord metaData = new ZNRecord(instance);
        metaData.setSimpleField(LiveInstanceProperty.SESSION_ID.toString(),
            UUID.randomUUID().toString());
        _liveInstances.add(metaData);
      }
      _externalView = IdealStateCalculatorForStorageNode.calculateIdealState(
          _instances, _partitions, _replicas, _db, "MASTER", "SLAVE");

    }

    @Override
    public boolean isConnected()
    {
      return true;
    }

    @Override
    public ClusterDataAccessor getDataAccessor()
    {
      return _accessor;
    }


    @Override
    public String getInstanceName()
    {
      return "localhost_12919";
    }

    @Override
    public InstanceType getInstanceType()
    {
      return InstanceType.PARTICIPANT;
    }
  }

  class TestMessageHandlerFactory implements MessageHandlerFactory
  {
    class TestMessageHandler extends MessageHandler
    {

      public TestMessageHandler(Message message, NotificationContext context)
      {
        super(message, context);
        // TODO Auto-generated constructor stub
      }

      @Override
      public CMTaskResult handleMessage() throws InterruptedException
      {
        CMTaskResult result = new CMTaskResult();
        result.setSuccess(true);
        return result;
      }

      @Override
      public void onError( Exception e, ErrorCode code, ErrorType type)
      {
        // TODO Auto-generated method stub
        
      }
    }
    @Override
    public MessageHandler createHandler(Message message,
        NotificationContext context)
    {
      // TODO Auto-generated method stub
      return new TestMessageHandler(message, context);
    }

    @Override
    public String getMessageType()
    {
      // TODO Auto-generated method stub
      return "TestingMessageHandler";
    }

    @Override
    public void reset()
    {
      // TODO Auto-generated method stub

    }
  }

  @Test()
  public void TestMessageSend()
  {
    ClusterManager manager = new MockClusterManager();
    DefaultMessagingService svc = new DefaultMessagingService(manager);
    TestMessageHandlerFactory factory = new TestMessageHandlerFactory();
    svc.registerMessageHandlerFactory(factory.getMessageType(), factory);

    Criteria recipientCriteria = new Criteria();
    recipientCriteria.setInstanceName("localhost_12919");
    recipientCriteria.setRecipientInstanceType(InstanceType.PARTICIPANT);
    recipientCriteria.setSelfExcluded(true);

    Message template = new Message(factory.getMessageType(), UUID.randomUUID().toString());
    AssertJUnit.assertEquals(0, svc.send(recipientCriteria, template));

    recipientCriteria.setSelfExcluded(false);
    AssertJUnit.assertEquals(1, svc.send(recipientCriteria, template));


    recipientCriteria.setSelfExcluded(false);
    recipientCriteria.setInstanceName("%");
    recipientCriteria.setResourceGroup("DB");
    recipientCriteria.setResourceKey("%");
    AssertJUnit.assertEquals(200, svc.send(recipientCriteria, template));

    recipientCriteria.setSelfExcluded(true);
    recipientCriteria.setInstanceName("%");
    recipientCriteria.setResourceGroup("DB");
    recipientCriteria.setResourceKey("%");
    AssertJUnit.assertEquals(159, svc.send(recipientCriteria, template));

    recipientCriteria.setSelfExcluded(true);
    recipientCriteria.setInstanceName("%");
    recipientCriteria.setResourceGroup("DB");
    recipientCriteria.setResourceKey("%");
    AssertJUnit.assertEquals(159, svc.send(recipientCriteria, template));

    recipientCriteria.setSelfExcluded(true);
    recipientCriteria.setInstanceName("localhost_12920");
    recipientCriteria.setResourceGroup("DB");
    recipientCriteria.setResourceKey("%");
    AssertJUnit.assertEquals(39, svc.send(recipientCriteria, template));


    recipientCriteria.setSelfExcluded(true);
    recipientCriteria.setInstanceName("localhost_12920");
    recipientCriteria.setRecipientInstanceType(InstanceType.CONTROLLER);
    recipientCriteria.setResourceGroup("DB");
    recipientCriteria.setResourceKey("%");
    AssertJUnit.assertEquals(1, svc.send(recipientCriteria, template));
  }
}
