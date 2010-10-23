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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.infinispan.Cache;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachemanagerlistener.annotation.CacheStopped;
import org.infinispan.notifications.cachemanagerlistener.event.CacheStoppedEvent;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.jboss.ha.core.framework.server.CoreGroupCommunicationService;
import org.jboss.ha.framework.server.lock.SharedLocalYieldingClusterLockManager;
import org.jboss.logging.Logger;
import org.jgroups.Channel;

/**
 * @author Vladimir Blagojevic
 * @author Paul Ferraro
 */
@Listener(sync = false)
public class DefaultLockManagerSource implements LockManagerSource
{
   /** The scope assigned to our group communication service */
   public static final Short SCOPE_ID = Short.valueOf((short) 223);
   /** The service name of the group communication service */
   public static final String SERVICE_NAME = "SFSBOWNER";
   
   private static final Logger log = Logger.getLogger(DefaultLockManagerSource.class);
   
   private static final Map<String, LockManagerEntry> lockManagers = new HashMap<String, LockManagerEntry>();
   
   /**
    * {@inheritDoc}
    * @see org.jboss.web.tomcat.service.session.distributedcache.ispn.LockManagerSource#getLockManager(org.infinispan.Cache)
    */
   @Override
   public SharedLocalYieldingClusterLockManager getLockManager(Cache<?, ?> cache)
   {
      if (!cache.getConfiguration().getCacheMode().isClustered()) return null;
      
      EmbeddedCacheManager container = (EmbeddedCacheManager) cache.getCacheManager();
      String clusterName = container.getGlobalConfiguration().getClusterName();
      
      synchronized (lockManagers)
      {
         LockManagerEntry entry = lockManagers.get(clusterName);
         
         if (entry == null)
         {
            trace("Starting lock manager for cluster %s", clusterName);
            
            entry = new LockManagerEntry(cache);
            
            container.addListener(this);
            
            lockManagers.put(clusterName, entry);
         }
         
         trace("Registering %s with lock manager for cluster %s", cache.getName(), clusterName);
         
         entry.addCache(cache.getName());
         
         return entry.getLockManager();
      }
   }
   
   private static class LockManagerEntry
   {
      private SharedLocalYieldingClusterLockManager lockManager;
      private CoreGroupCommunicationService service;
      private Set<String> caches = new HashSet<String>();
      
      LockManagerEntry(Cache<?, ?> cache)
      {
         JGroupsTransport transport = (JGroupsTransport) cache.getAdvancedCache().getRpcManager().getTransport();
         Channel channel = transport.getChannel();
         
         this.service = new CoreGroupCommunicationService();
         this.service.setChannel(channel);
         this.service.setScopeId(SCOPE_ID);
         
         try
         {
            this.service.start();
         }
         catch (Exception e)
         {
            throw new IllegalStateException("Unexpected exception while starting group communication service for " + channel.getClusterName());
         }
         
         this.lockManager = new SharedLocalYieldingClusterLockManager(SERVICE_NAME, this.service, this.service);
         
         try
         {
            this.lockManager.start();
         }
         catch (Exception e)
         {
            this.service.stop();
            throw new IllegalStateException("Unexpected exception while starting lock manager for " + channel.getClusterName());
         }
      }
      
      SharedLocalYieldingClusterLockManager getLockManager()
      {
         return this.lockManager;
      }
      
      synchronized void addCache(String cacheName)
      {
         this.caches.add(cacheName);
      }
      
      synchronized boolean removeCache(String cacheName)
      {
         this.caches.remove(cacheName);
         
         boolean empty = this.caches.isEmpty();
         
         if (empty)
         {
            try
            {
               this.lockManager.stop();
            }
            catch (Exception e)
            {
               log.warn(e.getMessage(), e);
            }
            try
            {
               this.service.stop();
            }
            catch (Exception e)
            {
               log.warn(e.getMessage(), e);
            }
         }
         
         return empty;
      }
   }
   
   @CacheStopped
   public void stopped(CacheStoppedEvent event)
   {
      String clusterName = event.getCacheManager().getGlobalConfiguration().getClusterName();
      
      synchronized (lockManagers)
      {
         LockManagerEntry entry = lockManagers.get(clusterName);
         
         if (entry != null)
         {
            trace("Deregistering %s from lock manager for cluster %s", event.getCacheName(), clusterName);
            
            // Returns true if this was the last cache
            if (entry.removeCache(event.getCacheName()))
            {
               trace("Stopped lock manager for cluster %s", clusterName);
               
               lockManagers.remove(clusterName);
            }
         }
      }
   }
   
   private static void trace(String message, Object... args)
   {
      if (log.isTraceEnabled())
      {
         log.trace(String.format(message, args));
      }
   }
}
