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

import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ejb.EJBException;
import javax.ejb.NoSuchEJBException;

import org.infinispan.Cache;
import org.infinispan.context.Flag;
import org.infinispan.distribution.DistributionManager;
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
import org.jboss.ha.framework.server.lock.SharedLocalYieldingClusterLockManager;
import org.jboss.ha.framework.server.lock.SharedLocalYieldingClusterLockManager.LockResult;
import org.jboss.ha.framework.server.lock.TimeoutException;
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
   private static final ThreadLocal<Boolean> localActivity = new ThreadLocal<Boolean>();
   
   @SuppressWarnings("unchecked")
   // Need to cast since ContextClassLoaderSwitcher.NewInstance does not generically implement PrivilegedAction<ContextClassLoaderSwitcher>
   private static final ContextClassLoaderSwitcher switcher = (ContextClassLoaderSwitcher) AccessController.doPrivileged(ContextClassLoaderSwitcher.INSTANTIATOR);
   
   // Defined in initialize(...)
   Map<Object, Future<Void>> removeFutures;
   Map<Object, Future<Void>> evictFutures;
   Cache<Object, StatefulBeanContext> cache;   
   Logger log;
   
   final ThreadFactory threadFactory;
   private final CacheSource cacheSource;
   private final LockManagerSource lockManagerSource;
   private final CacheInvoker invoker;

   private final AtomicInteger createCount = new AtomicInteger(0);
   private final AtomicInteger passivatedCount = new AtomicInteger(0);
   private final AtomicInteger removeCount = new AtomicInteger(0);
   private final AtomicBoolean resetTotalSize = new AtomicBoolean(true);
   
   private volatile int totalSize = 0;
   
   private StatefulContainer container;
   private CacheConfig cacheConfig;
   private ScheduledExecutorService executor;
   private SharedLocalYieldingClusterLockManager lockManager;

   // Defined in start()
   private WeakReference<ClassLoader> classLoaderRef;

   public InfinispanStatefulCache(CacheSource cacheSource, LockManagerSource lockManagerSource, CacheInvoker invoker, ThreadFactory threadFactory)
   {
      this.cacheSource = cacheSource;
      this.lockManagerSource = lockManagerSource;
      this.invoker = invoker;
      this.threadFactory = threadFactory;
   }

   @Override
   public void initialize(EJBContainer container) throws Exception
   {
      this.container = (StatefulContainer) container;
      this.log = Logger.getLogger(this.getClass().getName() + "." + this.container.getEjbName());
      
      this.cache = this.cacheSource.getCache(this.container);
      this.lockManager = this.lockManagerSource.getLockManager(this.cache);
      this.cacheConfig = this.container.getAnnotation(CacheConfig.class);
      
      if (this.cacheConfig.removalTimeoutSeconds() > 0)
      {
         this.removeFutures = new ConcurrentHashMap<Object, Future<Void>>();
      }
      if (this.cacheConfig.idleTimeoutSeconds() > 0)
      {
         this.evictFutures = new ConcurrentHashMap<Object, Future<Void>>();
      }
   }

   @Override
   public void start()
   {
      this.classLoaderRef = new WeakReference<ClassLoader>(this.container.getClassloader());
      
      if (!this.cache.getStatus().allowInvocations())
      {
         this.cache.start();
      }
      
      this.cache.addListener(this);
      
      if ((this.removeFutures != null) || (this.evictFutures != null))
      {
         final String threadName = "SFSB Removal/Eviction Thread - " + this.container.getObjectName().getCanonicalName();
         
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
      
      if (this.cache != null)
      {
         this.cache.removeListener(this);
         this.cache.getAdvancedCache().withFlags(Flag.CACHE_MODE_LOCAL).clear();
         this.cache.stop();
      }

      if (this.classLoaderRef != null)
      {
         this.classLoaderRef.clear();
      }
   }
   
   @Override
   public StatefulBeanContext peek(Object id) throws NoSuchEJBException
   {
      this.trace("peek(%s)", id);
      return this.get(id, false);
   }

   @Override
   public void release(StatefulBeanContext bean)
   {
      this.trace("release(%s)", bean.getId());
      synchronized (bean)
      {
         this.setInUse(bean, false);
      }
      
      this.releaseSessionOwnership(bean.getId(), false);
   }

   @Override
   public void replicate(StatefulBeanContext bean)
   {
      this.trace("replicate(%s)", bean.getId());
      // StatefulReplicationInterceptor should only pass us the ultimate
      // parent context for a tree of nested beans, which should always be
      // a standard StatefulBeanContext
      if (bean instanceof NestedStatefulBeanContext)
      {
         throw new IllegalArgumentException("Received unexpected replicate call for nested context " + bean.getId());
      }
      
      this.putInCache(bean);
   }

   @Override
   public void remove(final Object id)
   {
      this.trace("remove(%s)", id);
      Operation<StatefulBeanContext> operation = new Operation<StatefulBeanContext>()
      {
         @Override
         public StatefulBeanContext invoke(Cache<Object, StatefulBeanContext> cache)
         {
            return cache.get(id);
         }
      };
      
      this.acquireSessionOwnership(id, false);
      boolean remove = false;
      
      try
      {
         StatefulBeanContext bean = this.invoker.invoke(this.cache, operation);
   
         if (bean == null)
         {
            throw new NoSuchEJBException("Could not find bean: " + id);
         }
         
         if (!bean.isRemoved())
         {
            this.container.destroy(bean);
         }
         else
         {
            this.trace("remove(%s): was already removed from pool", id);
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
            
            remove = true;
         }
         else
         {
            // We can't remove the context as it contains live nested beans
            // But, we must replicate it so other nodes know the parent is removed!
            this.putInCache(bean);
            
            this.trace("remove(%s): cannot yet be removed from the cache", id);
         }
         
         if (this.removeFutures != null)
         {
            Future<Void> future = this.removeFutures.remove(id);
            if (future != null)
            {
               future.cancel(false);
            }
         }
   
         this.removeCount.incrementAndGet();
         this.resetTotalSize.set(true);
      }
      finally
      {
         this.releaseSessionOwnership(id, remove);
      }
   }

   @Override
   public StatefulBeanContext create(Class<?>[] initTypes, Object[] initValues)
   {
      StatefulBeanContext bean = this.create();
      this.trace("Caching context %s of type %s", bean.getId(), bean.getClass().getName());
      
      this.acquireSessionOwnership(bean.getId(), true);
      
      try
      {
         this.putInCache(bean);
         
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
      StatefulBeanContext bean = (StatefulBeanContext) this.container.createBeanContext();
      
      DistributionManager manager = this.cache.getAdvancedCache().getDistributionManager();
      
      if (manager != null)
      {
         // If using distribution mode, ensure that bean will cache locally
         while (!manager.isLocal(bean.getId()))
         {
            bean = new InfinispanStatefulBeanContext(this.container, bean.getInstance());
         }
      }

      // Tell context how to handle replication
      CacheConfig config = this.container.getAnnotation(CacheConfig.class);
      if (config != null)
      {
         bean.setReplicationIsPassivation(config.replicationIsPassivation());
      }

      // this is for propagated extended PC's
      bean = bean.pushContainedIn();

      this.container.pushContext(bean);
      try
      {
         this.container.injectBeanContext(bean);
      }
      finally
      {
         this.container.popContext();
         // this is for propagated extended PC's
         bean.popContainedIn();
      }

      this.container.invokePostConstruct(bean);

      return bean;
   }
   
   @Override
   public StatefulBeanContext get(Object id) throws EJBException
   {
      this.trace("get(%s)", id);
      
      if (this.acquireSessionOwnership(id, false) == LockResult.ACQUIRED_FROM_CLUSTER)
      {
         this.cache.getAdvancedCache().withFlags(Flag.CACHE_MODE_LOCAL).evict(id);
      }
      
      return this.get(id, true);
   }

   @Override
   public StatefulBeanContext get(final Object id, boolean markInUse) throws EJBException
   {
      this.trace("get(%s, %s)", id, markInUse);
      StatefulBeanContext bean = this.getFromCache(id);
      
      if (bean == null)
      {
         throw new NoSuchEJBException(String.format("Could not find stateful bean: %s", id));
      }
      else if (markInUse && bean.isRemoved())
      {
         throw new NoSuchEJBException(String.format("Could not find stateful bean: %s (bean was marked as removed)", id));
      }
      
      bean.postReplicate();
      
      if (markInUse)
      {
         synchronized (bean)
         {
            this.setInUse(bean, true);
         }
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
      if (this.removeFutures != null)
      {
         return this.removeFutures.size();
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
      return (this.cache != null) ? this.cache.getStatus().allowInvocations() : false;
   }

   @CacheEntryActivated
   public void activated(CacheEntryActivatedEvent<Object, StatefulBeanContext> event)
   {
      if (event.isPre()) return;
      // Needed in case this cache is shared
      if ((event.getValue() == null) || !(event.getValue() instanceof StatefulBeanContext)) return;
      
      this.trace("activated(%s)", event.getKey());
      StatefulBeanContext bean = event.getValue();
      
      this.passivatedCount.decrementAndGet();
      this.resetTotalSize.set(true);
      
      if (localActivity.get() == Boolean.TRUE)
      {
         SwitchContext switchContext = switcher.getSwitchContext();
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
            if (classLoader != null)
            {
               switchContext.reset();
            }
         }
      }
   }

   @CacheEntryPassivated
   public void passivated(CacheEntryPassivatedEvent<Object, StatefulBeanContext> event)
   {
      if (!event.isPre()) return;
      // Needed in case this cache is shared
      if ((event.getValue() == null) || !(event.getValue() instanceof StatefulBeanContext)) return;
      
      Object key = event.getKey();
      this.trace("passivated(%s)", key);
      
      StatefulBeanContext bean = event.getValue();
      
      SwitchContext switchContext = switcher.getSwitchContext();
      ClassLoader classLoader = this.classLoaderRef.get();
      
      Boolean active = localActivity.get();
      localActivity.set(Boolean.TRUE);
      
      try
      {
         if (!bean.getCanPassivate())
         {
            // Abort the eviction
            throw new RuntimeException(String.format("Cannot passivate bean %s -- it or one if its children is currently in use", key));
         }
         
         this.passivatedCount.incrementAndGet();
         this.resetTotalSize.set(true);
         
         if (classLoader != null)
         {
            switchContext.setClassLoader(classLoader);
         }
         
         bean.passivateAfterReplication();
      }
      finally
      {
         localActivity.set(active);
         
         if (classLoader != null)
         {
            switchContext.reset();
         }
      }
   }
   
   private StatefulBeanContext getFromCache(final Object key)
   {
      Operation<StatefulBeanContext> operation = new Operation<StatefulBeanContext>()
      {
         @Override
         public StatefulBeanContext invoke(Cache<Object, StatefulBeanContext> cache)
         {
            return cache.get(key);
         }
      };
      
      Boolean active = localActivity.get();
      localActivity.set(Boolean.TRUE);
      try
      {
         return this.invoker.invoke(this.cache, operation);
      }
      finally
      {
         localActivity.set(active);
      }
   }
   
   private void putInCache(final StatefulBeanContext bean)
   {
      Operation<StatefulBeanContext> operation = new Operation<StatefulBeanContext>()
      {
         @Override
         public StatefulBeanContext invoke(Cache<Object, StatefulBeanContext> cache)
         {
            return cache.put(bean.getId(), bean);
         }
      };
      
      Boolean active = localActivity.get();
      localActivity.set(Boolean.TRUE);
      try
      {
         bean.preReplicate();
         
         this.invoker.invoke(this.cache, operation);
         
         bean.markedForReplication = false;
      }
      finally
      {
         localActivity.set(active);
      }
   }
   
   private void setInUse(StatefulBeanContext bean, boolean inUse)
   {
      bean.setInUse(inUse);
      bean.lastUsed = System.currentTimeMillis();
      
      Object id = bean.getId();
      
      if (this.removeFutures != null)
      {
         Future<Void> future = this.removeFutures.put(id, this.executor.schedule(new RemoveTask(id), this.cacheConfig.removalTimeoutSeconds(), TimeUnit.SECONDS));
         
         if (future != null)
         {
            future.cancel(true);
         }
      }
      if (this.evictFutures != null)
      {
         Future<Void> future = inUse ? this.evictFutures.remove(id) : this.evictFutures.put(id, this.executor.schedule(new EvictTask(id), this.cacheConfig.idleTimeoutSeconds(), TimeUnit.SECONDS));

         if (future != null)
         {
            future.cancel(true);
         }
      }
   }
   
   private LockResult acquireSessionOwnership(Object id, boolean newLock)
   {
      if (this.lockManager == null) return null;
      
      this.trace("Acquiring %slock on %s", newLock ? "new " : "", id);
      
      try
      {
         LockResult result = this.lockManager.lock(this.getBeanLockKey(id), this.cache.getConfiguration().getLockAcquisitionTimeout(), newLock);
         this.trace("Lock acquired (%s) on %s", result, id);
         return result;
      }
      catch (TimeoutException e)
      {
         throw new EJBException("Caught " + e.getClass().getSimpleName() + " acquiring ownership of " + id, e);
      }
      catch (InterruptedException e)
      {
         Thread.currentThread().interrupt();
         throw new EJBException("Interrupted while acquiring ownership of " + id, e);
      }
   }
   
   private void releaseSessionOwnership(Object id, boolean remove)
   {
      if (this.lockManager != null)
      {
         this.trace("Releasing %slock on %s", remove ? "and removing " : "", id);
         this.lockManager.unlock(this.getBeanLockKey(id), remove);
         this.trace("Released %slock on %s", remove ? "and removed " : "", id);
      }
   }
   
   private Serializable getBeanLockKey(Object id)
   {
      return this.cache.getName() + "/" + id.toString();
   }
   
   class RemoveTask implements Callable<Void>
   {
      private final Object id;
      
      RemoveTask(Object id)
      {
         this.id = id;
      }
      
      @Override
      public Void call()
      {
         try
         {
            InfinispanStatefulCache.this.remove(this.id);
         }
         finally
         {
            InfinispanStatefulCache.this.removeFutures.remove(this.id);
         }
         return null;
      }
   }
   
   class EvictTask implements Callable<Void>
   {
      private final Object id;
      
      EvictTask(Object id)
      {
         this.id = id;
      }
      
      @Override
      public Void call()
      {
         try
         {
            InfinispanStatefulCache.this.cache.evict(this.id);
         }
         finally
         {
            InfinispanStatefulCache.this.evictFutures.remove(this.id);
         }
         return null;
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
   
   private void trace(String message, Object... args)
   {
      if (this.log.isTraceEnabled())
      {
         this.log.trace(String.format(message, args));
      }
   }
}
