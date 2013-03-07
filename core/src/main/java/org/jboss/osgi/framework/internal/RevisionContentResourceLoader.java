package org.jboss.osgi.framework.internal;
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

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.jboss.modules.ClassSpec;
import org.jboss.modules.PackageSpec;
import org.jboss.modules.Resource;
import org.jboss.modules.ResourceLoader;
import org.jboss.osgi.framework.spi.VirtualFileResourceLoader;
import org.jboss.osgi.resolver.XPackageRequirement;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleWiring;

/**
 * An {@link ResourceLoader} that is backed by a {@link RevisionContent} pointing to an archive.
 *
 * @author thomas.diesler@jboss.com
 * @since 13-Jan-2010
 */
final class RevisionContentResourceLoader implements ResourceLoader {

    private final HostBundleRevision hostRev;
    private final RevisionContent revContent;
    private final VirtualFileResourceLoader delegate;

    RevisionContentResourceLoader(HostBundleRevision hostRev, RevisionContent revContent) {
        assert hostRev != null : "Null hostRev";
        assert revContent != null : "Null revContent";
        this.delegate = new VirtualFileResourceLoader(revContent.getVirtualFile());
        this.revContent = revContent;
        this.hostRev = hostRev;
    }

    @Override
    public String getRootName() {
        return delegate.getRootName();
    }

    @Override
    public ClassSpec getClassSpec(String fileName) throws IOException {
        return delegate.getClassSpec(fileName);
    }

    @Override
    public PackageSpec getPackageSpec(String name) throws IOException {
        return delegate.getPackageSpec(name);
    }

    @Override
    public Resource getResource(String path) {
        URL url = revContent.getEntry(path);
        return url != null ? new URLResource(url) : null;
    }

    @Override
    public String getLibrary(String name) {
        return null;
    }

    @Override
    public Collection<String> getPaths() {
        return delegate.getPaths();
    }

    @Override
    public Collection<String> listResources(String path, String pattern) {
        // Filter substituted packages
        BundleWiring wiring = hostRev.getBundle().adapt(BundleWiring.class);
        List<BundleRequirement> preqs = wiring != null ? wiring.getRequirements(PackageNamespace.PACKAGE_NAMESPACE) : null;
        if (preqs != null) {
            String packagename = path.replace('/', '.');
            for (BundleRequirement req : preqs) {
                XPackageRequirement preq = (XPackageRequirement) req;
                if (packagename.equals(preq.getPackageName())) {
                    return Collections.emptyList();
                }
            }
        }
        return delegate.listResources(path, pattern);
    }

    @Override
    public String toString() {
        return revContent.toString();
    }
}
