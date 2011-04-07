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
package org.jboss.test.osgi.framework;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.HashMap;
import java.util.Map;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.BeforeClass;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.service.packageadmin.PackageAdmin;
import org.osgi.service.startlevel.StartLevel;

/**
 * Test framework bootstrap options.
 * 
 * @author thomas.diesler@jboss.com
 * @since 29-Apr-2010
 */
public class FrameworkLaunchTestCase extends OSGiFrameworkTest {

    @BeforeClass
    public static void beforeClass() {
        // prevent framework creation
    }

    @Test
    public void testFrameworkStartStop() throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put("org.osgi.framework.storage", "target/osgi-store");
        props.put("org.osgi.framework.storage.clean", "onFirstInit");

        FrameworkFactory factory = ServiceLoader.loadService(FrameworkFactory.class);
        Framework framework = factory.newFramework(props);

        assertNotNull("Framework not null", framework);
        assertBundleState(Bundle.INSTALLED, framework.getState());

        framework.init();
        assertBundleState(Bundle.STARTING, framework.getState());
        
        BundleContext systemContext = framework.getBundleContext();
        assertNotNull("BundleContext not null", systemContext);
        Bundle systemBundle = systemContext.getBundle();
        assertNotNull("Bundle not null", systemBundle);
        assertEquals("System bundle id", 0, systemBundle.getBundleId());
        assertEquals("System bundle name", Constants.SYSTEM_BUNDLE_SYMBOLICNAME, systemBundle.getSymbolicName());
        assertEquals("System bundle location", Constants.SYSTEM_BUNDLE_LOCATION, systemBundle.getLocation());
        
        Bundle[] bundles = systemContext.getBundles();
        assertEquals("System bundle available", 1, bundles.length);
        assertEquals("System bundle id", 0, bundles[0].getBundleId());
        assertEquals("System bundle name", Constants.SYSTEM_BUNDLE_SYMBOLICNAME, bundles[0].getSymbolicName());
        assertEquals("System bundle location", Constants.SYSTEM_BUNDLE_LOCATION, bundles[0].getLocation());
        
        ServiceReference paRef = systemContext.getServiceReference(PackageAdmin.class.getName());
        PackageAdmin packageAdmin = (PackageAdmin) systemContext.getService(paRef);
        assertNotNull("PackageAdmin not null", packageAdmin);

        ServiceReference slRef = systemContext.getServiceReference(StartLevel.class.getName());
        StartLevel startLevel = (StartLevel) systemContext.getService(slRef);
        assertNotNull("StartLevel not null", startLevel);
        assertEquals("Framework start level", 0, startLevel.getStartLevel());
        
        framework.start();
        assertBundleState(Bundle.ACTIVE, framework.getState());

        framework.stop();
        framework.waitForStop(2000);
        assertBundleState(Bundle.RESOLVED, framework.getState());
    }
}