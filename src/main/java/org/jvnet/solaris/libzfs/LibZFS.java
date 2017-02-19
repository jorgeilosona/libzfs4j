/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * You can obtain a copy of the license at usr/src/OPENSOLARIS.LICENSE
 * or http://www.opensolaris.org/os/licensing.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at usr/src/OPENSOLARIS.LICENSE.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */
package org.jvnet.solaris.libzfs;

import static org.jvnet.solaris.libzfs.jna.libzfs.LIBZFS;
import static org.jvnet.solaris.nvlist.jna.libnvpair.NV_UNIQUE_NAME;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jvnet.solaris.libzfs.jna.libzfs;
import org.jvnet.solaris.libzfs.jna.libzfs.zpool_iter_f;
import org.jvnet.solaris.libzfs.jna.libzfs_handle_t;
import org.jvnet.solaris.libzfs.jna.zfs_handle_t;
import org.jvnet.solaris.libzfs.jna.zfs_type_t;
import org.jvnet.solaris.libzfs.jna.zpool_handle_t;
import org.jvnet.solaris.nvlist.jna.nvlist_t;

import com.sun.jna.Pointer;

/* For the list of zfs features */
import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;


/**
 * Entry point to ZFS functionality in Java.
 * 
 * @author Kohsuke Kawaguchi
 */
public class LibZFS implements ZFSContainer {

    private libzfs_handle_t handle;

    /*
     * Track features available in current host ZFS ABI so we can use specific JNA
     * signatures. These will be populated as string tuples (key=value) as defined
     * in this library privately; beside auto-guesswork they can be populated from
     * environment variables (via appserver/init-script setup for hosting Jenkins)
     * as a fallback - but being private, admins should expect to reconfigure such
     * strings when this library gets updated... in reality, auto-guesswork should
     * (be implemented at all and then) do a good job.
     * Note: in this context, the "legacy" ABI refers to function signatures that
     * were in the OpenSolaris codebase prior to split between Oracle and illumos
     * and subsequently OpenZFS. The "openzfs" refers to the state of OpenZFS code
     * "Git HEAD" at the time of relevant edit of these libzfs.jar sources. Keep
     * in mind that downstream projects may lag accepting such changes, maybe for
     * years. For this reason, we keep a toggle for every function where we care
     * about signature differences, and in the (future) case that there are more
     * than these two levels for us to care about (e.g. HEADs in illumos-gate, ZoL
     * and *BSD downstreams of OpenZFS) some interim version strings may be defined
     * and if nothing is defined or value is unknown, then assume "legacy" mode.
     */
    private final Map<String,String> features = new HashMap<String, String>();

    /*package*/ String getFeature(String key) {
        return features.get(key);
    }

    private void initFeatures() {
        String v;
        String n;
        String abi; /* Cache the default while we make decisions */

        n = "LIBZFS4J_ABI";
        v = getSetting(n,null);
        if (v.equals("legacy") || v.equals("openzfs")) {
            /* Currently we recognize two values; later it may be more like openzfs-YYYY */
            abi = v;
        } else {
            /* Detect presence of e.g. feature flags routines == openzfs */
            abi = "legacy";
        }
        features.put(n,abi);

        n = "LIBZFS4J_ABI_zfs_iter_snapshots";
        v = getSetting(n,abi);
        features.put(n,v);
    }

    /**
     * Retrieves a feature setting from system property, then from env var.
     */
    private String getSetting(String key, String defaultValue) {
        String v = System.getProperty(key);
        if (v!=null)    return v;

        v = System.getenv(key);
        if (v!=null)    return v;

        return defaultValue;
    }

    public LibZFS() {
        handle = LIBZFS.libzfs_init();
        if (handle==null)
            throw new LinkageError("Failed to initialize libzfs");

        initFeatures();
    }

    /**
     * List up all the root file systems and return them.
     *
     * <p>
     * In ZFS, each zpool gets a top-level zfs file system automatically.
     * This method returns those top-level file systems.
     *
     * @return can be empty but never null.
     */
    public List<ZFSFileSystem> roots() {
        final List<ZFSFileSystem> r = new ArrayList<ZFSFileSystem>();
        LIBZFS.zfs_iter_root(handle, new libzfs.zfs_iter_f() {
            public int callback(zfs_handle_t handle, Pointer arg) {
                r.add(new ZFSFileSystem(LibZFS.this, handle));
                return 0;
            }
        }, null);
        return r;
    }

    /**
     * Lists up all the ZFS pools.
     *
     * @return can be empty but never null.
     */
    public List<ZFSPool> pools() {
        final List<ZFSPool> r = new ArrayList<ZFSPool>();
        LIBZFS.zpool_iter(handle, new zpool_iter_f() {
            public int callback(zpool_handle_t handle, Pointer arg) {
                r.add(new ZFSPool(LibZFS.this, handle));
                return 0;
            }
        }, null);
        return r;
    }

    /**
     * Gets the pool of the given name.
     */
    public ZFSPool getPool(String name) {
        zpool_handle_t h = LIBZFS.zpool_open(handle, name);
        if(h==null) return null;    // not found
        return new ZFSPool(this,h);
    }

    /**
     * Does a zfs dataset of the given name exist?
     * 
     * @param dataSetName
     *            the dataset name of check for.
     * @return does the dataset exist?
     */
    public boolean exists(final String dataSetName) {
        final boolean exists = exists(dataSetName, EnumSet.allOf(ZFSType.class));
        return exists;
    }

    /**
     * Does a zfs dataset of the given name and the given types exist?
     * 
     * @param name
     *            the dataset name of check for.
     * @param typeMask
     *            the specific zfs types to check for.
     * @return does the dataset exist?
     */
    public boolean exists(final String name, final Set<ZFSType> typeMask) {
        int mask = 0;
        for (ZFSType t : typeMask) {
            mask |= t.code;
        }

        final boolean exists = LIBZFS.zfs_dataset_exists(handle, name, mask);
        return exists;
    }

    /**
     * Does a zfs dataset of the given name and the given type exist?
     * 
     * @param dataSetName
     *            the dataset name of check for.
     * @param type
     *            the specific zfs type to check for.
     * @return does the dataset exist?
     */
    public boolean exists(final String dataSetName, final ZFSType type) {
        final boolean exists = exists(dataSetName, EnumSet.of(type));
        return exists;
    }

    /**
     * Create a ZFS Data Set of a given name and zfs type.
     *
     * @param dataSetName
     *            name of the dataset to create.
     * @param type
     *            the zfs type of dataset to create.
     * @return
     *      Never null. Created dataset.
     */
    public <T extends ZFSObject> T create(String dataSetName, Class<T> type) {
        return type.cast(create(dataSetName, ZFSType.fromType(type), null));
    }

    /**
     * Create a ZFS Data Set of a given name, zfs type and properties.
     * 
     * @param dataSetName
     *            Full name of the dataset to create, like "rpool/abc/def".
     * @param type
     *            the zfs type of dataset to create. Either {@link ZFSType#FILESYSTEM} or {@link ZFSType#VOLUME}.
     * @param props
     *            zfs dataset properties. Can be null.
     * @return created dataset.
     */
    public ZFSObject create(final String dataSetName, final ZFSType type,
            final Map<String, String> props) {
        final nvlist_t nvl = nvlist_t.alloc(NV_UNIQUE_NAME);
        if(props!=null) {
            for (Map.Entry<String, String> e : props.entrySet()) {
                nvl.put(e.getKey(), e.getValue());
            }
        }

        /* create intermediate directories */
        final String[] dirs = dataSetName.split("/");
        final StringBuilder sb = new StringBuilder(dirs[0]);
        for (int i = 1; i < dirs.length; i++) {
            sb.append('/').append(dirs[i]);
            if (!exists(sb.toString())) {
                if (LIBZFS.zfs_create(handle, sb.toString(), type.code, nvl) != 0) {
                    throw new ZFSException(this,"Failed to create "+dataSetName);
                }
            }
        }

        final ZFSObject dataSet = open(dataSetName);
        return dataSet;
    }

    /**
     * Open a ZFS Data Set of a given name.
     * 
     * @param dataSetName
     *            name of the dataset to open.
     * @return opened dataset, or null if no such dataset exists.
     */
    public ZFSObject open(final String dataSetName) {
        final ZFSObject dataSet = open(dataSetName, zfs_type_t.DATASET);
        return dataSet;
    }

    /**
     * Open a ZFS Data Set of a given name and type.
     * 
     * @param dataSetName
     *            name of the dataset to open.
     * @param mask
     *            the zfs type mask of dataset to open.
     * @return opened dataset, or null if no such dataset exists.
     */
    public ZFSObject open(final String dataSetName, final int /* zfs_type_t */mask) {
        zfs_handle_t h = LIBZFS.zfs_open(handle, dataSetName, mask);
        if(h==null) {
            int err = LIBZFS.libzfs_errno(handle);
            if(err==0)  return null;
            throw new ZFSException(this);
        }
        return ZFSObject.create(this,h);
    }

    /**
     * Opens a ZFS dataset of the given name and type.
     */
    public <T extends ZFSObject> T open(String dataSetName, Class<T> type) {
        return type.cast(open(dataSetName,ZFSType.fromType(type).code));
    }

    /**
     * Gets a {@link ZFSFileSystem} mounted at the given directory.
     *
     * @return
     *      null if no such file system exists.
     */
    public ZFSFileSystem getFileSystemByMountPoint(File dir) {
        dir = dir.getAbsoluteFile();
        for (ZFSFileSystem f : descendants(ZFSFileSystem.class)) {
            File mp = f.getMountPoint();
            if(mp!=null && mp.equals(dir))
                return f;
        }
        return null;
    }

    public List<ZFSFileSystem> children() {
        return roots();
    }

    public <T extends ZFSObject> List<T> children(Class<T> type) {
        if(type.isAssignableFrom(ZFSFileSystem.class))
            return (List)roots();
        else
            return Collections.emptyList();
    }

    public List<ZFSObject> descendants() {
        return children(ZFSObject.class);
    }

    public <T extends ZFSObject> List<T> descendants(Class<T> type) {
        ArrayList<T> r = new ArrayList<T>();
        r.addAll(children(type));
        for (ZFSFileSystem p : roots())
            r.addAll(p.descendants(type));
        return r;
    }

    /**
     * Returns {@link libzfs_handle_t} that this object wraps.
     * <p>
     * If the caller wants to use methods that don't yet have a high-level
     * binding, the returned {@link libzfs_handle_t} can be used directly in
     * conjunction with {@link libzfs#LIBZFS}.
     */
    public libzfs_handle_t getHandle() {
        return handle;
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        dispose();
    }

    /**
     * Eagerly releases the native resource associated with this wrapper,
     * instead of waiting for GC to take care of it.
     */
    public synchronized void dispose() {
        if (handle != null) {
            LIBZFS.libzfs_fini(handle);
            handle = null;
        }
    }
}
