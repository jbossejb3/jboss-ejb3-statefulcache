package org.jboss.ejb3.cache.infinispan;

import java.util.List;

import javax.ejb.Stateful;

import org.easymock.Capture;
import org.easymock.CaptureType;
import org.easymock.EasyMock;
import org.easymock.IMocksControl;
import org.infinispan.Cache;
import org.infinispan.config.Configuration;
import org.infinispan.config.Configuration.CacheMode;
import org.infinispan.manager.EmbeddedCacheManager;
import org.jboss.ejb3.annotation.CacheConfig;
import org.jboss.ejb3.stateful.StatefulContainer;
import org.jboss.ha.ispn.CacheContainerRegistry;
import org.junit.Assert;
import org.junit.Test;

public class DefaultCacheSourceTestCase extends StatefulContainerFactory
{
   @Test
   public void getCache() throws Exception
   {
      Configuration configuration = new Configuration();
      configuration.setCacheMode(CacheMode.REPL_SYNC);
      
      this.getCache(this.createContainer(TestBeanWithDefaults.class), CacheConfig.DEFAULT_CLUSTERED_OBJECT_NAME, null, configuration, CacheMode.REPL_SYNC, 2);
      this.getCache(this.createContainer(TestBeanWithCustomContainer.class), "container", null, configuration, CacheMode.REPL_SYNC, 2);
/*
      this.getCache(this.createContainer(TestBeanWithCustomContainerAndCache.class), "container", "cache", configuration, CacheMode.REPL_SYNC, 2);
      this.getCache(this.createContainer(TestBeanWithUnrecognizedProperties.class), CacheConfig.DEFAULT_CLUSTERED_OBJECT_NAME, null, configuration, CacheMode.REPL_SYNC, 2);
      this.getCache(this.createContainer(TestBeanWithReplAsync.class), CacheConfig.DEFAULT_CLUSTERED_OBJECT_NAME, null, configuration, CacheMode.REPL_ASYNC, 2);
      this.getCache(this.createContainer(TestBeanWithReplSync.class), CacheConfig.DEFAULT_CLUSTERED_OBJECT_NAME, null, configuration, CacheMode.REPL_SYNC, 2);
      this.getCache(this.createContainer(TestBeanWithLocal.class), CacheConfig.DEFAULT_CLUSTERED_OBJECT_NAME, null, configuration, CacheMode.LOCAL, 2);
      this.getCache(this.createContainer(TestBeanWithDistAsync.class), CacheConfig.DEFAULT_CLUSTERED_OBJECT_NAME, null, configuration, CacheMode.DIST_ASYNC, 2);
      this.getCache(this.createContainer(TestBeanWithDistSync.class), CacheConfig.DEFAULT_CLUSTERED_OBJECT_NAME, null, configuration, CacheMode.DIST_SYNC, 2);
      this.getCache(this.createContainer(TestBeanWithDistAsyncOwners.class), CacheConfig.DEFAULT_CLUSTERED_OBJECT_NAME, null, configuration, CacheMode.DIST_ASYNC, 4);
      this.getCache(this.createContainer(TestBeanWithDistSyncOwners.class), CacheConfig.DEFAULT_CLUSTERED_OBJECT_NAME, null, configuration, CacheMode.DIST_SYNC, 4);
      
      configuration.setCacheMode(CacheMode.DIST_SYNC);
      
      this.getCache(this.createContainer(TestBeanWithOwners.class), CacheConfig.DEFAULT_CLUSTERED_OBJECT_NAME, null, configuration, CacheMode.DIST_SYNC, 4);
*/
   }
   
   private void getCache(StatefulContainer ejbContainer, String containerName, String templateCacheName, Configuration configuration, CacheMode mode, int owners)
   {
      IMocksControl control = EasyMock.createStrictControl();
      CacheContainerRegistry registry = control.createMock(CacheContainerRegistry.class);
      EmbeddedCacheManager container = control.createMock(EmbeddedCacheManager.class);
      @SuppressWarnings("unchecked")
      Cache<Object, Object> cache = control.createMock(Cache.class);
      Configuration cacheConfiguration = configuration.clone();
      Capture<String> capturedCacheNames = new Capture<String>(CaptureType.ALL);
      
      DefaultCacheSource source = new DefaultCacheSource(registry);
      
      EasyMock.expect(registry.getCacheContainer(containerName)).andReturn(container);
      EasyMock.expect(container.defineConfiguration(EasyMock.capture(capturedCacheNames), EasyMock.eq(templateCacheName), EasyMock.eq(new Configuration()))).andReturn(cacheConfiguration);
      EasyMock.expect(container.getCache(EasyMock.capture(capturedCacheNames))).andReturn(cache);
      
      control.replay();
      
      Cache<Object, Object> result = source.getCache(ejbContainer);
      
      control.verify();
      
      Assert.assertSame(cache, result);
      
      Assert.assertSame(mode, cacheConfiguration.getCacheMode());
      Assert.assertEquals(owners, cacheConfiguration.getNumOwners());
      
      List<String> cacheNames = capturedCacheNames.getValues();
      
      Assert.assertEquals(ejbContainer.getDeploymentPropertyListString(), cacheNames.get(0));
      Assert.assertSame(cacheNames.get(0), cacheNames.get(1));
   }
/*
   @Test(expected = IllegalArgumentException.class)
   public void getBadCache() throws Exception
   {
      Configuration configuration = new Configuration();
      
      this.getBadCache(this.createContainer(TestBeanWithInvalidCacheMode.class), CacheConfig.DEFAULT_CLUSTERED_OBJECT_NAME, null, configuration);
      this.getBadCache(this.createContainer(TestBeanWithInvalidOwners.class), CacheConfig.DEFAULT_CLUSTERED_OBJECT_NAME, null, configuration);
   }
   
   private void getBadCache(StatefulContainer ejbContainer, String containerName, String templateCacheName, Configuration configuration)
   {
      IMocksControl control = EasyMock.createStrictControl();
      CacheContainerRegistry registry = control.createMock(CacheContainerRegistry.class);
      EmbeddedCacheManager container = control.createMock(EmbeddedCacheManager.class);
      Configuration cacheConfiguration = configuration.clone();
      Capture<String> capturedCacheNames = new Capture<String>(CaptureType.ALL);
      
      DefaultCacheSource source = new DefaultCacheSource(registry);
      
      EasyMock.expect(registry.getCacheContainer(containerName)).andReturn(container);
      EasyMock.expect(container.defineConfiguration(EasyMock.capture(capturedCacheNames), EasyMock.eq(templateCacheName), EasyMock.eq(new Configuration()))).andReturn(cacheConfiguration);
      
      control.replay();
      
      try
      {
         source.getCache(ejbContainer);
      }
      finally
      {
         control.verify();
      }
   }
*/   
   @Stateful
   @CacheConfig
   public static class TestBeanWithDefaults
   {
      
   }
   
   @Stateful
   @CacheConfig(name = "container")
   public static class TestBeanWithCustomContainer
   {
      
   }

   @Stateful
   @CacheConfig(name = "container/cache")
   public static class TestBeanWithCustomContainerAndCache
   {
      
   }   
/*   
   @Stateful
   @CacheConfig(properties = { @CacheProperty(name = "blah", value = "blah") })
   public static class TestBeanWithUnrecognizedProperties
   {
      
   }
   
   @Stateful
   @CacheConfig(properties = { @CacheProperty(name = "infinispan.blah", value = "blah") })
   public static class TestBeanWithInvalidProperties
   {
      
   }
   
   @Stateful
   @CacheConfig(properties = { @CacheProperty(name = "infinispan.mode", value = "blah") })
   public static class TestBeanWithInvalidCacheMode
   {
      
   }
   
   @Stateful
   @CacheConfig(properties = { @CacheProperty(name = "infinispan.owners", value = "blah") })
   public static class TestBeanWithInvalidOwners
   {
      
   }

   @Stateful
   @CacheConfig(properties = { @CacheProperty(name = "infinispan.mode", value = "REPL_ASYNC") })
   public static class TestBeanWithReplAsync
   {
      
   }
   
   @Stateful
   @CacheConfig(properties = { @CacheProperty(name = "infinispan.mode", value = "REPL_SYNC") })
   public static class TestBeanWithReplSync
   {
      
   }
   
   @Stateful
   @CacheConfig(properties = { @CacheProperty(name = "infinispan.mode", value = "LOCAL") })
   public static class TestBeanWithLocal
   {
      
   }
   
   @Stateful
   @CacheConfig(properties = { @CacheProperty(name = "infinispan.mode", value = "DIST_ASYNC") })
   public static class TestBeanWithDistAsync
   {
      
   }
   
   @Stateful
   @CacheConfig(properties = { @CacheProperty(name = "infinispan.mode", value = "DIST_SYNC") })
   public static class TestBeanWithDistSync
   {
      
   }

   @Stateful
   @CacheConfig(properties = { @CacheProperty(name = "infinispan.mode", value = "DIST_SYNC"), @CacheProperty(name = "infinispan.owners", value = "4") })
   public static class TestBeanWithDistSyncOwners
   {
      
   }

   @Stateful
   @CacheConfig(properties = { @CacheProperty(name = "infinispan.mode", value = "DIST_ASYNC"), @CacheProperty(name = "infinispan.owners", value = "4") })
   public static class TestBeanWithDistAsyncOwners
   {
      
   }

   @Stateful
   @CacheConfig(properties = { @CacheProperty(name = "infinispan.owners", value = "4") })
   public static class TestBeanWithOwners
   {
      
   }
*/
}
