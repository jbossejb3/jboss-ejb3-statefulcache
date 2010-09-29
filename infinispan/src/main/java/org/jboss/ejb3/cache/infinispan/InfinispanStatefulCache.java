/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.ejb3.cache.infinispan;

import java.lang.ref.WeakReference;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ejb.EJBException;
import javax.ejb.NoSuchEJBException;

import org.infinispan.Cache;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.lifecycle.ComponentStatus;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryActivated;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryPassivated;
import org.infinispan.notifications.cachelistener.event.CacheEntryActivatedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryPassivatedEvent;
import org.jboss.ejb3.EJBContainer;
import org.jboss.ejb3.annotation.CacheConfig;
import org.jboss.ejb3.cache.ClusteredStatefulCache;
import org.jboss.ejb3.stateful.NestedStatefulBeanContext;
import org.jboss.ejb3.stateful.StatefulBeanContext;
import org.jboss.ejb3.stateful.StatefulContainer;
import org.jboss.ha.ispn.invoker.CacheInvoker;
import org.jboss.logging.Logger;
import org.jboss.util.loading.ContextClassLoaderSwitcher;
import org.jboss.util.loading.ContextClassLoaderSwitcher.SwitchContext;

/**
 * @author Paul Ferraro
 */
@Listener
public class InfinispanStatefulCache implements ClusteredStatefulCache
{
   @SuppressWarnings("unchecked")
   // Need to cast since ContextClassLoaderSwitcher.NewInstance does not generically implement PrivilegedAction<ContextClassLoaderSwitcher>
   private final ContextClassLoaderSwitcher switcher = (ContextClassLoaderSwitcher) AccessController.doPrivileged(ContextClassLoaderSwitcher.INSTANTIATOR);
   
   private final CacheSource source;
   private final CacheInvoker invoker;
   final ThreadFactory threadFactory;

   private final AtomicInteger createCount = new AtomicInteger(0);
   private final AtomicInteger passivatedCount = new AtomicInteger(0);
   private final AtomicInteger removeCount = new AtomicInteger(0);
   private final AtomicBoolean resetTotalSize = new AtomicBoolean(true);
   
   private volatile int totalSize = 0;
   
   // Defined in initialize(...)
   long removalTimeout;
   Map<Object, Long> beans = null;
   Logger log;
   private StatefulContainer ejbContainer;
   private CacheConfig cacheConfig;
   private ScheduledExecutorService executor;
   private Cache<Object, StatefulBeanContext> cache;

   // Defined in start()
   private WeakReference<ClassLoader> classLoaderRef;
   
   public InfinispanStatefulCache(CacheSource source, CacheInvoker invoker, ThreadFactory threadFactory)
   {
      this.source = source;
      this.invoker = invoker;
      this.threadFactory = threadFactory;
   }

   @Override
   public void initialize(EJBContainer container) throws Exception
   {
      this.ejbContainer = (StatefulContainer) container;
      this.log = Logger.getLogger(getClass().getName() + "." + this.ejbContainer.getEjbName());
      
      this.cache = this.source.getCache(this.ejbContainer);
      this.cacheConfig = this.ejbContainer.getAnnotation(CacheConfig.class);
      
      this.removalTimeout = this.cacheConfig.removalTimeoutSeconds() * 1000L;
      
      if (this.removalTimeout > 0)
      {
         this.beans = new ConcurrentHashMap<Object, Long>();
      }
   }

   @Override
   public void start()
   {
      this.classLoaderRef = new WeakReference<ClassLoader>(this.ejbContainer.getClassloader());
      
      if (!this.cache.getStatus().allowInvocations())
      {
         this.cache.start();
      }
      
      this.cache.addListener(this);
      
      if (this.removalTimeout > 0)
      {
         final String threadName = "SFSB Removal Thread - " + this.ejbContainer.getObjectName().getCanonicalName();
         
         // Decorate our thread factory and customize thread name
         ThreadFactory threadFactory = new ThreadFactory()
         {
            @Override
            public Thread newThread(Runnable task)
            {
               final Thread thread = InfinispanStatefulCache.this.threadFactory.newThread(task);
               // Thread.setName() is a privileged action
               PrivilegedAction<Void> action = new PrivilegedAction<Void>()
               {
                  @Override
                  public Void run()
                  {
                     thread.setName(threadName);
                     return null;
                  }
               };
               AccessController.doPrivileged(action);
               return thread;
            }
         };
         
         this.executor = Executors.newScheduledThreadPool(1, threadFactory);
         this.executor.scheduleWithFixedDelay(new RemovalTimeoutTask(), this.removalTimeout, this.removalTimeout, TimeUnit.MILLISECONDS);
      }
      
      this.resetTotalSize.set(true);
   }
   
   @Override
   public void stop()
   {
      if (this.executor != null)
      {
         this.executor.shutdownNow();
      }
      
      this.cache.removeListener(this);
      this.cache.stop();
      
      this.classLoaderRef.clear();
   }
   
   @Override
   public StatefulBeanContext peek(Object id) throws NoSuchEJBException
   {
      return this.get(id, false);
   }

   @Override
   public void release(StatefulBeanContext bean)
   {
      synchronized (bean)
      {
         this.setInUse(bean, false);
      }
   }

   @Override
   public void replicate(final StatefulBeanContext bean)
   {
      // StatefulReplicationInterceptor should only pass us the ultimate
      // parent context for a tree of nested beans, which should always be
      // a standard StatefulBeanContext
      if (bean instanceof NestedStatefulBeanContext)
      {
         throw new IllegalArgumentException("Received unexpected replicate call for nested context " + bean.getId());
      }
      
      bean.preReplicate();
      
      Operation<StatefulBeanContext> operation = new Operation<StatefulBeanContext>()
      {
         @Override
         public StatefulBeanContext invoke(Cache<Object, StatefulBeanContext> cache)
         {
            return cache.put(bean.getId(), bean);
         }
      };
      
      this.invoker.invoke(this.cache, operation);
      
      bean.markedForReplication = false;
   }

   @Override
   public void remove(final Object id)
   {
      Operation<StatefulBeanContext> operation = new Operation<StatefulBeanContext>()
      {
         @Override
         public StatefulBeanContext invoke(Cache<Object, StatefulBeanContext> cache)
         {
            return cache.get(id);
         }
      };
      
      final StatefulBeanContext bean = this.invoker.invoke(this.cache, operation);

      if (bean == null)
      {
         throw new NoSuchEJBException("Could not find Stateful bean: " + id);
      }
      
      if (!bean.isRemoved())
      {
         this.ejbContainer.destroy(bean);
      }
      else if (this.log.isTraceEnabled())
      {
         this.log.trace("remove: " + id + " already removed from pool");
      }

      if (bean.getCanRemoveFromCache())
      {
         operation = new Operation<StatefulBeanContext>()
         {
            @Override
            public StatefulBeanContext invoke(Cache<Object, StatefulBeanContext> cache)
            {
               return cache.remove(id);
            }
         };
         
         this.invoker.invoke(this.cache, operation);
      }
      else
      {
         // We can't remove the context as it contains live nested beans
         // But, we must replicate it so other nodes know the parent is removed!
         operation = new Operation<StatefulBeanContext>()
         {
            @Override
            public StatefulBeanContext invoke(Cache<Object, StatefulBeanContext> cache)
            {
               return cache.put(id, bean);
            }
         };
         
         this.invoker.invoke(this.cache, operation);
         
         if(log.isTraceEnabled())
         {
            log.trace("remove: removed bean " + id + " cannot be removed from cache");
         }
      }
      
      if (this.beans != null)
      {
         this.beans.remove(id);
      }

      this.removeCount.incrementAndGet();
      this.resetTotalSize.set(true);
   }

   @Override
   public StatefulBeanContext create(Class<?>[] initTypes, Object[] initValues)
   {
      final StatefulBeanContext bean = this.create();
      
      if (this.log.isTraceEnabled())
      {
         this.log.trace("Caching context " + bean.getId() + " of type " + bean.getClass().getName());
      }
      
      try
      {
         bean.preReplicate();
         
         Operation<StatefulBeanContext> operation = new Operation<StatefulBeanContext>()
         {
            @Override
            public StatefulBeanContext invoke(Cache<Object, StatefulBeanContext> cache)
            {
               return cache.put(bean.getId(), bean);
            }
         };
         
         this.invoker.invoke(this.cache, operation);
         
         bean.markedForReplication = false;
         
         this.setInUse(bean, true);
         
         this.createCount.incrementAndGet();
         this.resetTotalSize.set(true);
         
         return bean;
      }
      catch (EJBException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new EJBException(e);
      }
   }

   /**
    * Copy of {@link org.jboss.ejb3.stateful.StatefulContainer#create(Class[], Object[])}
    * with additional logic to ensure that the generated bean will cache locally.
    * @see {@link org.jboss.ejb3.stateful.StatefulContainer#create(Class[], Object[])}
    */
   private StatefulBeanContext create()
   {
      StatefulBeanContext bean = (StatefulBeanContext) this.ejbContainer.createBeanContext();
      
      DistributionManager manager = this.cache.getAdvancedCache().getDistributionManager();
      
      if (manager != null)
      {
         // If using distribution mode, ensure that bean will cache locally
         while (!manager.isLocal(bean.getId()))
         {
            bean = new InfinispanStatefulBeanContext(bean.getContainer(), bean.getInstance());
         }
      }

      // Tell context how to handle replication
      CacheConfig config = this.ejbContainer.getAnnotation(CacheConfig.class);
      if (config != null)
      {
         bean.setReplicationIsPassivation(config.replicationIsPassivation());
      }

      // this is for propagated extended PC's
      bean = bean.pushContainedIn();

      this.ejbContainer.pushContext(bean);
      try
      {
         this.ejbContainer.injectBeanContext(bean);
      }
      finally
      {
         this.ejbContainer.popContext();
         // this is for propagated extended PC's
         bean.popContainedIn();
      }

      this.ejbContainer.invokePostConstruct(bean);

      return bean;
   }
   
   @Override
   public StatefulBeanContext get(Object id) throws EJBException
   {
      return this.get(id, true);
   }

   @Override
   public StatefulBeanContext get(final Object key, boolean markInUse) throws EJBException
   {
      Operation<StatefulBeanContext> getOperation = new Operation<StatefulBeanContext>()
      {
         @Override
         public StatefulBeanContext invoke(Cache<Object, StatefulBeanContext> cache)
         {
            return cache.get(key);
         }
      };
      
      StatefulBeanContext bean = this.invoker.invoke(this.cache, getOperation);
      
      if (bean == null)
      {
         throw new NoSuchEJBException("Could not find stateful bean: " + key);
      }
      else if (markInUse && bean.isRemoved())
      {
         throw new NoSuchEJBException("Could not find stateful bean: " + key +
                                      " (bean was marked as removed)");
      }
      
      bean.postReplicate();
      
      if (markInUse)
      {
         synchronized (bean)
         {
            this.setInUse(bean, true);
         }
      }

      if (this.log.isTraceEnabled())
      {
         this.log.trace("get: retrieved bean with cache id " + key);
      }

      return bean;
   }

   @Override
   public int getAvailableCount()
   {
      int maxSize = this.getMaxSize();
      return (maxSize < 0) ? maxSize : maxSize - this.getCurrentSize();
   }

   @Override
   public int getCacheSize()
   {
      return this.getTotalSize() - this.getPassivatedCount();
   }

   @Override
   public int getCreateCount()
   {
      return this.createCount.get();
   }

   @Override
   public int getCurrentSize()
   {
      return this.getCacheSize();
   }

   @Override
   public int getMaxSize()
   {
      return (this.cacheConfig == null) ? -1 : this.cacheConfig.maxSize();
   }

   @Override
   public int getPassivatedCount()
   {
      return this.passivatedCount.get();
   }

   @Override
   public int getRemoveCount()
   {
      return this.removeCount.get();
   }

   @Override
   public int getTotalSize()
   {
      if (this.beans != null)
      {
         return this.beans.size();
      }
      
      if (this.resetTotalSize.compareAndSet(true, false))
      {
         this.totalSize = this.cache.size();
      }
      
      return this.totalSize;
   }

   @Override
   public boolean isStarted()
   {
      return (this.cache != null) ? this.cache.getStatus() == ComponentStatus.RUNNING : false;
   }

   @CacheEntryActivated
   public void activated(CacheEntryActivatedEvent event)
   {
      if (event.isPre()) return;
      
      this.passivatedCount.decrementAndGet();
      this.resetTotalSize.set(true);

      StatefulBeanContext bean = this.cache.get(event.getKey());
      
      SwitchContext switchContext = this.switcher.getSwitchContext();
      ClassLoader classLoader = this.classLoaderRef.get();
      
      try
      {
         if (classLoader != null)
         {
            switchContext.setClassLoader(classLoader);
         }

         bean.activateAfterReplication();
      }
      finally
      {
         switchContext.reset();
      }
   }

   @CacheEntryPassivated
   public void passivated(CacheEntryPassivatedEvent event)
   {
      if (!event.isPre()) return;
      
      Object key = event.getKey();
      StatefulBeanContext bean = this.cache.get(event.getKey());
      
      SwitchContext switchContext = this.switcher.getSwitchContext();
      ClassLoader classLoader = this.classLoaderRef.get();
      
      try
      {
         if (classLoader != null)
         {
            switchContext.setClassLoader(classLoader);
         }

         if (!bean.getCanPassivate())
         {
            // Abort the eviction
            throw new RuntimeException("Cannot passivate bean %s" + key +
                  " -- it or one if its children is currently in use");
         }
         
         bean.passivateAfterReplication();
      }
      finally
      {
         switchContext.reset();
      }
      
      this.passivatedCount.incrementAndGet();
      this.resetTotalSize.set(true);
   }
   
   private void setInUse(StatefulBeanContext bean, boolean inUse)
   {
      bean.setInUse(inUse);
      bean.lastUsed = System.currentTimeMillis();
      
      if (this.beans != null)
      {
         this.beans.put(bean.getId(), Long.valueOf(bean.lastUsed));
      }
   }
   
   class RemovalTimeoutTask implements Runnable
   {
      @Override
      public void run()
      {
         long now = System.currentTimeMillis();

         for (Map.Entry<Object, Long> entry: InfinispanStatefulCache.this.beans.entrySet())
         {
            if (now - entry.getValue().longValue() >= InfinispanStatefulCache.this.removalTimeout)
            {
               Object key = entry.getKey();
               
               try
               {
                  InfinispanStatefulCache.this.remove(key);
               }
               catch (NoSuchEJBException e)
               {
                  InfinispanStatefulCache.this.beans.remove(key);
               }
               catch (Exception e)
               {
                  InfinispanStatefulCache.this.log.error("problem removing SFSB " + key, e);
               }
            }
         }
      }
   }
   
   // Simplified CacheInvoker.Operation using specific key/value types
   interface Operation<R> extends CacheInvoker.Operation<Object, StatefulBeanContext, R>
   {
   }
   
   public static class InfinispanStatefulBeanContext extends StatefulBeanContext
   {
      // Expose constructor
      InfinispanStatefulBeanContext(StatefulContainer container, Object bean)
      {
         super(container, bean);
      }
   }
}
