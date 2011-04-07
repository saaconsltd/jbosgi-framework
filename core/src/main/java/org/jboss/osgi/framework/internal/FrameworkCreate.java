/*
 * JBoss, Home of Professional Open Source
 * Copyright 2005, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
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
package org.jboss.osgi.framework.internal;

import org.jboss.logging.Logger;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.osgi.framework.launch.Framework;

/**
 * A service that represents the CREATED state of the {@link Framework}.
 * 
 * When this services has started, the system bundle context is availbale as
 * well as the basic infrastructure to register OSGi services.
 *
 * @author thomas.diesler@jboss.com
 * @since 04-Apr-2011
 */
final class FrameworkCreate extends FrameworkService<FrameworkCreate> {

    // Provide logging
    static final Logger log = Logger.getLogger(FrameworkCreate.class);

    private final FrameworkState frameworkState;
    
    static FrameworkState addService(ServiceTarget serviceTarget, BundleManager bundleManager) {
        FrameworkState frameworkState = new FrameworkState(bundleManager);
        FrameworkCreate service = new FrameworkCreate(frameworkState);
        ServiceBuilder<FrameworkCreate> builder = serviceTarget.addService(Services.FRAMEWORK_CREATE, service);
        builder.addDependency(Services.BUNDLE_DEPLOYMENT_PLUGIN, BundleDeploymentPlugin.class, frameworkState.injectedBundleDeployment);
        builder.addDependency(Services.BUNDLE_STORAGE_PLUGIN, BundleStoragePlugin.class, frameworkState.injectedBundleStorage);
        builder.addDependency(Services.FRAMEWORK_EVENTS_PLUGIN, FrameworkEventsPlugin.class, frameworkState.injectedFrameworkEvents);
        builder.addDependency(Services.MODULE_MANGER_PLUGIN, ModuleManagerPlugin.class, frameworkState.injectedModuleManager);
        builder.addDependency(Services.NATIVE_CODE_PLUGIN, NativeCodePlugin.class, frameworkState.injectedNativeCode);
        builder.addDependency(Services.RESOLVER_PLUGIN, ResolverPlugin.class, frameworkState.injectedResolverPlugin);
        builder.addDependency(Services.SERVICE_MANAGER_PLUGIN, ServiceManagerPlugin.class, frameworkState.injectedServiceManager);
        builder.addDependency(Services.SYSTEM_BUNDLE, SystemBundleState.class, frameworkState.injectedSystemBundle);
        builder.setInitialMode(Mode.ON_DEMAND);
        builder.install();
        return frameworkState;
    }

    
    private FrameworkCreate(FrameworkState frameworkState) {
        this.frameworkState = frameworkState;
    }

    @Override
    public void start(StartContext context) throws StartException {
        super.start(context);
        getBundleManager().injectedFramework.inject(this);
    }

    @Override
    public void stop(StopContext context) {
        super.stop(context);
        getBundleManager().injectedFramework.uninject();
    }


    @Override
    public FrameworkCreate getValue() throws IllegalStateException, IllegalArgumentException {
        return this;
    }

    @Override
    FrameworkState getFrameworkState() {
        return frameworkState;
    }
}