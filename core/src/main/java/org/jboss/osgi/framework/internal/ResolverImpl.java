/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2012 JBoss by Red Hat
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */
package org.jboss.osgi.framework.internal;

import static org.jboss.osgi.framework.FrameworkLogger.LOGGER;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.State;
import org.jboss.msc.service.ServiceName;
import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.FrameworkModuleLoader;
import org.jboss.osgi.framework.spi.FrameworkWiringLock;
import org.jboss.osgi.framework.spi.FutureServiceValue;
import org.jboss.osgi.framework.spi.LockManager;
import org.jboss.osgi.framework.spi.LockManager.LockContext;
import org.jboss.osgi.framework.spi.LockManager.Method;
import org.jboss.osgi.framework.spi.ModuleManager;
import org.jboss.osgi.framework.spi.NativeCode;
import org.jboss.osgi.metadata.NativeLibraryMetaData;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XIdentityCapability;
import org.jboss.osgi.resolver.XPackageRequirement;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XResolveContext;
import org.jboss.osgi.resolver.XResolver;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.felix.StatelessResolver;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.resource.Wiring;
import org.osgi.service.resolver.ResolutionException;
import org.osgi.service.resolver.ResolveContext;

/**
 * The resolver plugin.
 *
 * @author thomas.diesler@jboss.com
 * @since 15-Feb-2012
 */
public final class ResolverImpl extends StatelessResolver implements XResolver {

    private final BundleManagerPlugin bundleManager;
    private final NativeCode nativeCode;
    private final ModuleManager moduleManager;
    private final FrameworkModuleLoader moduleLoader;
    private final XEnvironment environment;
    private final LockManager lockManager;

    public ResolverImpl(BundleManager bundleManager, NativeCode nativeCode, ModuleManager moduleManager, FrameworkModuleLoader moduleLoader,
            XEnvironment environment, LockManager lockManager) {
        this.bundleManager = BundleManagerPlugin.assertBundleManagerPlugin(bundleManager);
        this.nativeCode = nativeCode;
        this.moduleManager = moduleManager;
        this.moduleLoader = moduleLoader;
        this.environment = environment;
        this.lockManager = lockManager;
    }

    @Override
    public XResolveContext createResolveContext(XEnvironment environment, Collection<? extends Resource> mandatory, Collection<? extends Resource> optional) {
        Collection<Resource> manres = filterSingletons(mandatory);
        Collection<Resource> optres = new HashSet<Resource>(optional != null ? optional : Collections.<Resource> emptySet());
        appendOptionalFragments(mandatory, optres);
        appendOptionalHostBundles(mandatory, optres);
        return super.createResolveContext(environment, manres, optres);
    }

    @Override
    public synchronized Map<Resource, List<Wire>> resolve(ResolveContext resolveContext) throws ResolutionException {
        LockContext lockContext = null;
        try {
            FrameworkWiringLock wireLock = lockManager.getItemForType(FrameworkWiringLock.class);
            lockContext = lockManager.lockItems(Method.RESOLVE, wireLock);
            return super.resolve(resolveContext);
        } finally {
            lockManager.unlockItems(lockContext);
        }
    }

    @Override
    public synchronized Map<Resource, Wiring> resolveAndApply(XResolveContext resolveContext) throws ResolutionException {

        Map<Resource, List<Wire>> wiremap;
        Map<Resource, Wiring> wirings;

        LockContext lockContext = null;
        try {
            FrameworkWiringLock wireLock = lockManager.getItemForType(FrameworkWiringLock.class);
            lockContext = lockManager.lockItems(Method.RESOLVE, wireLock);
            wiremap = super.resolve(resolveContext);
            wirings = applyResolverResults(wiremap);
        } finally {
            lockManager.unlockItems(lockContext);
        }

        // Send the {@link BundleEvent.RESOLVED} event outside the lock
        sendBundleResolvedEvents(wiremap);
        return wirings;
    }

    private void appendOptionalFragments(Collection<? extends Resource> mandatory, Collection<Resource> optional) {
        Collection<Capability> hostcaps = getHostCapabilities(mandatory);
        if (hostcaps.isEmpty() == false) {
            optional.addAll(findAttachableFragments(hostcaps));
        }
    }

    // Append the set of all unresolved resources if there is at least one optional package requirement
    private void appendOptionalHostBundles(Collection<? extends Resource> mandatory, Collection<Resource> optional) {
        for (Resource res : mandatory) {
            for (Requirement req : res.getRequirements(PackageNamespace.PACKAGE_NAMESPACE)) {
                XPackageRequirement preq = (XPackageRequirement) req;
                if (preq.isOptional()) {
                    for (XBundle bundle : bundleManager.getBundles(Bundle.INSTALLED)) {
                        XResource auxrev = bundle.getBundleRevision();
                        if (!bundle.isFragment() && !mandatory.contains(auxrev)) {
                            optional.add(auxrev);
                        }
                    }
                    return;
                }
            }
        }
    }

    private Collection<Capability> getHostCapabilities(Collection<? extends Resource> resources) {
        Collection<Capability> result = new HashSet<Capability>();
        for (Resource res : resources) {
            List<Capability> caps = res.getCapabilities(HostNamespace.HOST_NAMESPACE);
            if (caps.size() == 1)
                result.add(caps.get(0));
        }
        return result;
    }

    private Collection<Resource> filterSingletons(Collection<? extends Resource> resources) {
        Map<String, Resource> singletons = new HashMap<String, Resource>();
        List<Resource> result = new ArrayList<Resource>(resources);
        Iterator<Resource> iterator = result.iterator();
        while (iterator.hasNext()) {
            XResource xres = (XResource) iterator.next();
            XIdentityCapability icap = xres.getIdentityCapability();
            if (icap.isSingleton()) {
                if (singletons.get(icap.getSymbolicName()) != null) {
                    iterator.remove();
                } else {
                    singletons.put(icap.getSymbolicName(), xres);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    private Collection<XResource> findAttachableFragments(Collection<? extends Capability> hostcaps) {
        Set<XResource> result = new HashSet<XResource>();
        for (XResource res : environment.getResources(IdentityNamespace.TYPE_FRAGMENT)) {
            Requirement req = res.getRequirements(HostNamespace.HOST_NAMESPACE).get(0);
            XRequirement xreq = (XRequirement) req;
            for (Capability cap : hostcaps) {
                if (xreq.matches(cap)) {
                    result.add(res);
                }
            }
        }
        if (result.isEmpty() == false) {
            LOGGER.debugf("Adding attachable fragments: %s", result);
        }
        return result;
    }

    private Map<Resource, Wiring> applyResolverResults(Map<Resource, List<Wire>> wiremap) throws ResolutionException {

        // [TODO] Revisit how we apply the resolution results
        // An exception in one of the steps may leave the framework partially modified

        // Transform the wiremap to {@link BundleRevision} and {@link BundleWire}
        Map<BundleRevision, List<BundleWire>> brevmap = new LinkedHashMap<BundleRevision, List<BundleWire>>();
        for (Entry<Resource, List<Wire>> entry : wiremap.entrySet()) {
            List<BundleWire> bwires = new ArrayList<BundleWire>();
            List<Wire> wires = new ArrayList<Wire>();
            for (Wire wire : entry.getValue()) {
                AbstractBundleWire bwire = new AbstractBundleWire(wire);
                bwires.add(bwire);
                wires.add(bwire);
            }
            Resource res = entry.getKey();
            brevmap.put((BundleRevision) res, bwires);
            wiremap.put(res, wires);
        }

        // Attach the fragments to host
        attachFragmentsToHost(brevmap);

        // Resolve native code libraries if there are any
        try {
            resolveNativeCodeLibraries(brevmap);
        } catch (BundleException ex) {
            throw new ResolutionException(ex);
        }

        // For every resolved host bundle create the {@link ModuleSpec}
        addModules(brevmap);

        // For every resolved host bundle create a {@link Module} service
        createModuleServices(brevmap);

        // For every resolved host bundle create a Bundle.RESOLVED service
        createBundleServices(brevmap);

        // Construct and apply the resource wiring map
        Map<Resource, Wiring> wirings = environment.updateWiring(wiremap);
        for (Entry<Resource, Wiring> entry : wirings.entrySet()) {
            XBundleRevision res = (XBundleRevision) entry.getKey();
            res.addAttachment(Wiring.class, entry.getValue());
        }

        // Change the bundle state to RESOLVED
        setBundleStatesToResolved(brevmap);

        return wirings;
    }

    private void attachFragmentsToHost(Map<BundleRevision, List<BundleWire>> wiremap) {
        for (Map.Entry<BundleRevision, List<BundleWire>> entry : wiremap.entrySet()) {
            XBundleRevision brev = (XBundleRevision) entry.getKey();
            if (brev.isFragment()) {
                FragmentBundleRevision fragRev = (FragmentBundleRevision) brev;
                for (BundleWire wire : entry.getValue()) {
                    BundleCapability cap = wire.getCapability();
                    if (HostNamespace.HOST_NAMESPACE.equals(cap.getNamespace())) {
                        HostBundleRevision hostRev = (HostBundleRevision) cap.getResource();
                        fragRev.attachToHost(hostRev);
                    }
                }
            }
        }
    }

    private void resolveNativeCodeLibraries(Map<BundleRevision, List<BundleWire>> wiremap) throws BundleException {
        for (Map.Entry<BundleRevision, List<BundleWire>> entry : wiremap.entrySet()) {
            XBundleRevision brev = (XBundleRevision) entry.getKey();
            if (brev instanceof UserBundleRevision) {
                UserBundleRevision userRev = (UserBundleRevision) brev;
                Deployment deployment = userRev.getDeployment();

                // Resolve the native code libraries, if there are any
                NativeLibraryMetaData libMetaData = deployment.getAttachment(NativeLibraryMetaData.class);
                if (libMetaData != null) {
                    nativeCode.resolveNativeCode(userRev);
                }
            }
        }
    }

    private void addModules(Map<BundleRevision, List<BundleWire>> wiremap) {
        for (Map.Entry<BundleRevision, List<BundleWire>> entry : wiremap.entrySet()) {
            XBundleRevision brev = (XBundleRevision) entry.getKey();
            if (brev.isFragment() == false) {
                List<BundleWire> wires = wiremap.get(brev);
                moduleManager.addModule(brev, wires);
            }
        }
    }

    private void createModuleServices(Map<BundleRevision, List<BundleWire>> wiremap) {
        for (Map.Entry<BundleRevision, List<BundleWire>> entry : wiremap.entrySet()) {
            XBundleRevision brev = (XBundleRevision) entry.getKey();
            List<BundleWire> wires = entry.getValue();
            XBundle bundle = brev.getBundle();
            if (bundle != null && bundle.getBundleId() != 0 && !brev.isFragment()) {
                moduleLoader.createModuleService(brev, wires);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void createBundleServices(Map<BundleRevision, List<BundleWire>> wiremap) {
        for (Map.Entry<BundleRevision, List<BundleWire>> entry : wiremap.entrySet()) {
            XBundleRevision brev = (XBundleRevision) entry.getKey();
            XBundle bundle = brev.getBundle();
            if (bundle != null && bundle.getBundleId() != 0 && !brev.isFragment()) {
                HostBundleRevision hostRev = HostBundleRevision.assertHostRevision(brev);
                HostBundleState hostState = hostRev.getBundleState();
                BundleManager bundleManager = hostState.adapt(BundleManager.class);
                ServiceContainer serviceContainer = bundleManager.getServiceContainer();
                ServiceName serviceName = hostState.getServiceName(Bundle.RESOLVED);
                ServiceController<HostBundleState> controller = (ServiceController<HostBundleState>) serviceContainer.getService(serviceName);
                if (controller != null) {
                    FutureServiceValue<HostBundleState> future = new FutureServiceValue<HostBundleState>(controller, State.REMOVED);
                    try {
                        future.get(10, TimeUnit.SECONDS);
                    } catch (Exception ex) {
                        // ignore
                    }
                }
                hostRev.createResolvedService(hostState.getServiceTarget());
            }
        }
    }

    private void setBundleStatesToResolved(Map<BundleRevision, List<BundleWire>> wiremap) {
        for (Map.Entry<BundleRevision, List<BundleWire>> entry : wiremap.entrySet()) {
            Bundle bundle = entry.getKey().getBundle();
            if (bundle instanceof AbstractBundleState) {
                AbstractBundleState bundleState = (AbstractBundleState)bundle;
				bundleState.changeState(Bundle.RESOLVED, 0);
            }
        }
    }

    private void sendBundleResolvedEvents(Map<Resource, List<Wire>> wiremap) {
        for (Entry<Resource, List<Wire>> entry : wiremap.entrySet()) {
            XBundleRevision brev = (XBundleRevision) entry.getKey();
            Bundle bundle = brev.getBundle();
            if (bundle instanceof AbstractBundleState) {
                AbstractBundleState bundleState = (AbstractBundleState)bundle;
                if (bundleManager.isFrameworkCreated()) {
                    bundleState.fireBundleEvent(BundleEvent.RESOLVED);
                }
            }
        }
    }
}
