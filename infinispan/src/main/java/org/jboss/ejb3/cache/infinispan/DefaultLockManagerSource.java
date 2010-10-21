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
import java.util.Map;

import org.infinispan.Cache;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.jboss.ha.core.framework.server.CoreGroupCommunicationService;
import org.jboss.ha.framework.server.lock.SharedLocalYieldingClusterLockManager;
import org.jgroups.Channel;

/**
 * @author Vladimir Blagojevic
 * @author Paul Ferraro
 */
public class DefaultLockManagerSource implements LockManagerSource
{
   /** The scope assigned to our group communication service */
   public static final Short SCOPE_ID = Short.valueOf((short) 223);
   /** The service name of the group communication service */
   public static final String SERVICE_NAME = "SFSBOWNER";
   
   private final Map<String, SharedLocalYieldingClusterLockManager> lockManagers = new HashMap<String, SharedLocalYieldingClusterLockManager>();
   
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
      
      synchronized (this.lockManagers)
      {
         SharedLocalYieldingClusterLockManager lockManager = this.lockManagers.get(clusterName);
         
         if (lockManager == null)
         {
            JGroupsTransport transport = (JGroupsTransport) cache.getAdvancedCache().getRpcManager().getTransport();
            Channel channel = transport.getChannel();
            
            CoreGroupCommunicationService gcs = new CoreGroupCommunicationService();
            gcs.setChannel(channel);
            gcs.setScopeId(SCOPE_ID);
            
            try
            {
               gcs.start();
            }
            catch (Exception e)
            {
               throw new IllegalStateException("Unexpected exception while starting group communication service for " + clusterName);
            }
            
            lockManager = new SharedLocalYieldingClusterLockManager(SERVICE_NAME, gcs, gcs);
            
            try
            {
               lockManager.start();
            }
            catch (Exception e)
            {
               gcs.stop();
               throw new IllegalStateException("Unexpected exception while starting lock manager for " + clusterName);
            }
            
            this.lockManagers.put(clusterName, lockManager);
         }
         
         return lockManager;
      }
   }
}
