package org.jboss.ejb3.cache.infinispan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.spi.InitialContextFactory;
import javax.transaction.TransactionManager;

import org.easymock.EasyMock;
import org.jboss.aop.AspectManager;
import org.jboss.aop.Domain;
import org.jboss.aop.advice.AdviceFactory;
import org.jboss.aop.advice.AdviceStack;
import org.jboss.aop.advice.AspectDefinition;
import org.jboss.aop.advice.GenericAspectFactory;
import org.jboss.aop.advice.InterceptorFactory;
import org.jboss.aop.advice.Scope;
import org.jboss.aspects.currentinvocation.CurrentInvocationInterceptor;
import org.jboss.ejb3.Ejb3Deployment;
import org.jboss.ejb3.core.context.CurrentInvocationContextInterceptor;
import org.jboss.ejb3.stateful.StatefulContainer;
import org.jboss.ejb3.test.common.MetaDataHelper;
import org.jboss.metadata.ejb.jboss.JBossSessionBeanMetaData;
import org.jboss.tm.TransactionManagerLocator;
import org.junit.AfterClass;
import org.junit.BeforeClass;

public class StatefulContainerFactory
{
   static Context context = EasyMock.createStrictMock(Context.class);
   private static String initialContextFactory;
   
   private static TransactionManager txManager = EasyMock.createStrictMock(TransactionManager.class);
   private static AtomicBoolean init = new AtomicBoolean(true);
   
   @BeforeClass
   public static void beforeClass() throws NamingException
   {
      if (init.compareAndSet(true, false))
      {
         initialContextFactory = System.setProperty(Context.INITIAL_CONTEXT_FACTORY, MockInitialContextFactory.class.getName());
         
         EasyMock.expect(context.lookup("java:/TransactionManager")).andReturn(txManager);
         
         EasyMock.replay(context);
         
         TransactionManagerLocator.locateTransactionManager();
         
         EasyMock.verify(context);
         EasyMock.reset(context);
      }
   }
   
   @AfterClass
   public static void afterClass()
   {
      if (initialContextFactory != null)
      {
         System.setProperty(Context.INITIAL_CONTEXT_FACTORY, initialContextFactory);
      }
      else
      {
         System.clearProperty(Context.INITIAL_CONTEXT_FACTORY);
      }
   }
   
   protected StatefulContainer createContainer(Class<?> bean) throws Exception
   {
      EasyMock.expect(context.getEnvironment()).andReturn(null);

      EasyMock.replay(context);
      
      @SuppressWarnings("deprecation")
      Ejb3Deployment deployment = MockEjb3Deployment.create(MockDeploymentUnit.create());
      
      EasyMock.verify(context);
      EasyMock.reset(context);
      
      Domain domain = new Domain(new AspectManager(), "Test", false);
      GenericAspectFactory aspectFactory = new GenericAspectFactory(CurrentInvocationInterceptor.class.getName(), null);
      AspectDefinition def = new AspectDefinition("CurrentInvocationInterceptor", Scope.PER_VM, aspectFactory);
      domain.addAspectDefinition(def);
      AdviceFactory factory = new AdviceFactory(def, "invoke");
      GenericAspectFactory aspectFactory2 = new GenericAspectFactory(CurrentInvocationContextInterceptor.class.getName(), null);
      AspectDefinition def2 = new AspectDefinition("CurrentInvocationContextInterceptor", Scope.PER_VM, aspectFactory2);
      domain.addAspectDefinition(def2);
      AdviceFactory factory2 = new AdviceFactory(def2, "invoke");
      AdviceStack stack = new AdviceStack("InjectionCallbackStack", new ArrayList<InterceptorFactory>(Arrays.asList(factory, factory2)));
      domain.addAdviceStack(stack);
      
      JBossSessionBeanMetaData metaData = MetaDataHelper.getMetadataFromBeanImplClass(bean);
      return new StatefulContainer(Thread.currentThread().getContextClassLoader(), bean.getName(), "TestBean", domain, new Hashtable<Object, Object>(), deployment, metaData, Executors.newCachedThreadPool());
   }
   
   public static class MockInitialContextFactory implements InitialContextFactory
   {
      @Override
      public Context getInitialContext(Hashtable<?, ?> env) throws NamingException
      {
         return context;
      }
   }
}
