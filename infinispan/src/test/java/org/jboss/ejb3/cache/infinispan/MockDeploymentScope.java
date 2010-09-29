/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
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

import java.util.Collection;

import javax.naming.NameNotFoundException;

import org.jboss.deployers.structure.spi.DeploymentUnit;
import org.jboss.ejb3.DeploymentScope;
import org.jboss.ejb3.EJBContainer;
import org.jboss.ejb3.Ejb3Deployment;
import org.jboss.ejb3.common.resolvers.spi.EjbReference;
import org.jboss.ejb3.common.resolvers.spi.EjbReferenceResolver;
import org.jboss.util.NotImplementedException;

/**
 * MockDeploymentScope
 * 
 * A mock DeploymentScope which searches only inside of its own deployment
 *
 * @author <a href="mailto:andrew.rubinger@jboss.org">ALR</a>
 * @version $Revision: $
 */
public class MockDeploymentScope implements DeploymentScope
{

   /*
    * TODO This mock is a work in progress; the EjbReferenceResolver is not capable
    * of resolving to EJBContainer, only to JNDI Names.  So we must:
    * 
    * 1) Bring in the DeploymentScope impl from AS to use here
    * 2) Make this mock compatible
    * 3) Rework the injection framework to the pluggable resolvers (this
    * is the long-term solution)
    */

   // --------------------------------------------------------------------------------||
   // Instance Members ---------------------------------------------------------------||
   // --------------------------------------------------------------------------------||
   private Ejb3Deployment deployment;

   private DeploymentUnit du;

   private EjbReferenceResolver resolver;

   // --------------------------------------------------------------------------------||
   // Constructor --------------------------------------------------------------------||
   // --------------------------------------------------------------------------------||

   public MockDeploymentScope(Ejb3Deployment deployment, DeploymentUnit du, EjbReferenceResolver resolver)
   {
      this.deployment = deployment;
      this.du = du;
      this.resolver = resolver;
   }

   // --------------------------------------------------------------------------------||
   // Required Implementations -------------------------------------------------------||
   // --------------------------------------------------------------------------------||

   /* (non-Javadoc)
    * @see org.jboss.ejb3.DeploymentScope#findRelativeDeployment(java.lang.String)
    */
   public Ejb3Deployment findRelativeDeployment(String relativeName)
   {
      return this.deployment;
   }

   /* (non-Javadoc)
    * @see org.jboss.ejb3.DeploymentScope#getBaseName()
    */
   public String getBaseName()
   {
      throw new UnsupportedOperationException();
   }

   /* (non-Javadoc)
    * @see org.jboss.ejb3.DeploymentScope#getEjbContainer(java.lang.Class, java.lang.String)
    */
   public EJBContainer getEjbContainer(Class businessIntf, String vfsContext) throws NameNotFoundException
   {
      return this.getEjbContainer(null, businessIntf, vfsContext);
   }

   /* (non-Javadoc)
    * @see org.jboss.ejb3.DeploymentScope#getEjbContainer(java.lang.String, java.lang.Class, java.lang.String)
    */
   public EJBContainer getEjbContainer(String ejbLink, Class businessIntf, String vfsContext)
   {
      // Make a reference
      EjbReference reference = new EjbReference(null, businessIntf.getName(), null);

      throw new NotImplementedException("@see Comments in " + MockDeploymentScope.class.getName());

   }

   /* (non-Javadoc)
    * @see org.jboss.ejb3.DeploymentScope#getEjbDeployments()
    */
   public Collection<Ejb3Deployment> getEjbDeployments()
   {
      throw new UnsupportedOperationException();
   }

   /* (non-Javadoc)
    * @see org.jboss.ejb3.DeploymentScope#getShortName()
    */
   public String getShortName()
   {
      throw new UnsupportedOperationException();
   }

   /* (non-Javadoc)
    * @see org.jboss.ejb3.DeploymentScope#register(org.jboss.ejb3.Ejb3Deployment)
    */
   public void register(Ejb3Deployment deployment)
   {
      // TODO Auto-generated method stub

   }

   /* (non-Javadoc)
    * @see org.jboss.ejb3.DeploymentScope#unregister(org.jboss.ejb3.Ejb3Deployment)
    */
   public void unregister(Ejb3Deployment deployment)
   {
      // TODO Auto-generated method stub

   }

   /* (non-Javadoc)
    * @see org.jboss.ejb3.javaee.JavaEEApplication#getName()
    */
   public String getName()
   {
      // TODO Auto-generated method stub
      return null;
   }

}
