/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.jenkins.plugins.artifact_manager_jclouds;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.MutableBlobMetadata;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import hudson.remoting.Callable;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider.HttpMethod;
import jenkins.util.VirtualFile;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

/**
 * <a href="https://jclouds.apache.org/start/blobstore/">JClouds BlobStore Guide</a>
 */
@Restricted(NoExternalUse.class)
public class JCloudsVirtualFile extends VirtualFile {

    private static final long serialVersionUID = -5126878907895121335L;

    private static final Logger LOGGER = Logger.getLogger(JCloudsVirtualFile.class.getName());

    @NonNull
    private BlobStoreProvider provider;
    @NonNull
    private final String container;
    @NonNull
    private final String key;
    @CheckForNull
    private transient Blob blob;

    @SuppressFBWarnings(value = "SE_TRANSIENT_FIELD_NOT_RESTORED",
            justification = "This field is expected to be loaded by a provider instead of deserialization.")
    @CheckForNull
    private transient BlobStoreContext context;

    public JCloudsVirtualFile(@NonNull BlobStoreProvider provider, @NonNull String container, @NonNull String key) {
        this.provider = provider;
        this.container = container;
        this.key = key;
        assert !key.isEmpty();
        assert !key.startsWith("/");
        assert !key.endsWith("/");
    }

    private JCloudsVirtualFile(@NonNull JCloudsVirtualFile related, @NonNull String key) {
        this(related.provider, related.container, key);
        context = related.context;
    }

    /**
     * Build jclouds blob context that is the base for all operations
     */
    @Restricted(NoExternalUse.class) // testing only
    BlobStoreContext getContext() throws IOException {
        if (context == null) {
            context = provider.getContext();
        }
        return context;
    }

    /**
     * Returns the base name
     */
    @Override
    public String getName() {
        return key.replaceFirst(".+/", "");
    }

    private Blob getBlob() throws IOException {
        if (blob == null) {
            LOGGER.log(Level.FINE, "checking for existence of blob {0} / {1}", new Object[] {container, key});
            try {
                HeadObjectResponse object = provider.head(container, key);
                blob = getContext().getBlobStore().blobBuilder(key).build();
                MutableBlobMetadata metadata = getBlob().getMetadata();
                metadata.setContainer(container);
                metadata.setSize(object.contentLength());
                metadata.setLastModified(Date.from(object.lastModified()));
            } catch (NoSuchKeyException ignored) { }
        }
        return blob;
    }

    @Override
    public URI toURI() {
        return provider.toURI(container, key);
    }

    @Override
    public URL toExternalURL() throws IOException {
        return provider.toExternalURL(getBlob(), HttpMethod.GET);
    }

    @Override
    public VirtualFile getParent() {
        // undefined to go outside …/artifacts
        return new JCloudsVirtualFile(this, key.replaceFirst("/[^/]+$", ""));
    }

    @Override
    public boolean isDirectory() throws IOException {
        String keyS = key + "/";
        CacheFrame frame = findCacheFrame(keyS);
        if (frame != null) {
            LOGGER.log(Level.FINER, "cache hit on directory status of {0} / {1}", new Object[] {container, key});
            String relSlash = keyS.substring(frame.root.length()); // "" or "sub/dir/"
            return frame.children.keySet().stream().anyMatch(f -> f.startsWith(relSlash));
        }
        LOGGER.log(Level.FINE, "checking directory status {0} / {1}", new Object[] {container, key});
        return !provider.listAll(container, key + "/").isEmpty();
    }

    @Override
    public boolean isFile() throws IOException {
        CacheFrame frame = findCacheFrame(key);
        if (frame != null) {
            String rel = key.substring(frame.root.length());
            CachedMetadata metadata = frame.children.get(rel);
            LOGGER.log(Level.FINER, "cache hit on file status of {0} / {1}", new Object[] {container, key});
            return metadata != null;
        }
        LOGGER.log(Level.FINE, "checking file status {0} / {1}", new Object[] {container, key});
        return getBlob().getMetadata().getSize() != null;
    }

    @Override
    public boolean exists() throws IOException {
        return isDirectory() || isFile();
    }

    @Override
    public VirtualFile[] list() throws IOException {
        String keyS = key + "/";
        CacheFrame frame = findCacheFrame(keyS);
        if (frame != null) {
            LOGGER.log(Level.FINER, "cache hit on listing of {0} / {1}", new Object[] {container, key});
            String relSlash = keyS.substring(frame.root.length()); // "" or "sub/dir/"
            return frame.children.keySet().stream(). // filenames relative to frame root
                filter(f -> f.startsWith(relSlash)). // those inside this dir
                map(f -> f.substring(relSlash.length()).replaceFirst("/.+", "")). // just the file simple name, or direct subdir name
                distinct(). // ignore duplicates if have multiple files under one direct subdir
                map(simple -> new JCloudsVirtualFile(this, keyS + simple)). // direct children
                toArray(VirtualFile[]::new);
        }
        VirtualFile[] list;
        try {
            list = provider.listAll(provider.getContainer(), key).stream().map(meta -> new JCloudsVirtualFile(this, meta.key().replaceFirst("/$", "")))
              .toArray(VirtualFile[]::new);
        } catch (RuntimeException x) {
            throw new IOException(x);
        }
        LOGGER.log(Level.FINEST, "Listing files from {0} {1}: {2}",
                new String[] { container, key, Arrays.toString(list) });
        return list;
    }

    @Override
    public VirtualFile child(String name) {
        return new JCloudsVirtualFile(this, key + "/" + name);
    }

    @Override
    public long length() throws IOException {
        CacheFrame frame = findCacheFrame(key);
        if (frame != null) {
            String rel = key.substring(frame.root.length());
            CachedMetadata metadata = frame.children.get(rel);
            LOGGER.log(Level.FINER, "cache hit on length of {0} / {1}", new Object[] {container, key});
            return metadata != null ? metadata.length : 0;
        }
        LOGGER.log(Level.FINE, "checking length {0} / {1}", new Object[] {container, key});
        MutableBlobMetadata metadata = getBlob().getMetadata();
        Long size = metadata == null ? Long.valueOf(0) : metadata.getSize();
        return size == null ? 0 : size;
    }

    @Override
    public long lastModified() throws IOException {
        CacheFrame frame = findCacheFrame(key);
        if (frame != null) {
            String rel = key.substring(frame.root.length());
            CachedMetadata metadata = frame.children.get(rel);
            LOGGER.log(Level.FINER, "cache hit on lastModified of {0} / {1}", new Object[] {container, key});
            return metadata != null ? metadata.lastModified : 0;
        }
        LOGGER.log(Level.FINE, "checking modification time {0} / {1}", new Object[] {container, key});
        MutableBlobMetadata metadata = getBlob().getMetadata();
        return metadata == null || metadata.getLastModified() == null ? 0 : metadata.getLastModified().getTime();
    }

    @Override
    public boolean canRead() throws IOException {
        return true;
    }

    @Override
    public InputStream open() throws IOException {
        LOGGER.log(Level.FINE, "reading {0} / {1}", new Object[] {container, key});
        if (isDirectory()) {
            // That is what java.io.FileInputStream.open throws
            throw new FileNotFoundException(String.format("%s/%s (Is a directory)", container, key));
        }
        if (!isFile()) {
            throw new FileNotFoundException(
                    String.format("%s/%s (No such file or directory)", container, key));
        }
        return provider.get(container, key);
    }

    /**
     * Cache of metadata collected during {@link #run}.
     * Keys are {@link #container}.
     * Values are a stack of cache frames, one per nested {@link #run} call.
     */
    private static final ThreadLocal<Map<String, Deque<CacheFrame>>> cache = ThreadLocal.withInitial(HashMap::new);

    private static final class CacheFrame {
        /** {@link #key} of the root virtual file plus a trailing {@code /} */
        final String root;
        /**
         * Information about all known (recursive) child <em>files</em> (not directories).
         * Keys are {@code /}-separated relative paths.
         * If the root itself happened to be a file, that information is not cached.
         */
        final Map<String, CachedMetadata> children;
        CacheFrame(String root, Map<String, CachedMetadata> children) {
            this.root = root;
            this.children = children;
        }
    }

    /**
     * Record that a given file exists.
     */
    private static final class CachedMetadata {
        final long length, lastModified;
        CachedMetadata(long length, long lastModified) {
            this.length = length;
            this.lastModified = lastModified;
        }
    }

    @Override
    public <V> V run(Callable<V, IOException> callable) throws IOException {
        LOGGER.log(Level.FINE, "enter cache {0} / {1}", new Object[] {container, key});
        Deque<CacheFrame> stack = cacheFrames();
        Map<String, CachedMetadata> saved = new HashMap<>();
        int prefixLength = key.length() + /* / */1;
        for (ObjectIdentifier sm : provider.listAll(provider.getContainer(), key)) {
            Long length = sm.size();
            if (length != null) {
                Date lastModified = Date.from(sm.lastModifiedTime());
                saved.put(sm.key().substring(prefixLength), new CachedMetadata(length, lastModified.getTime()));
            }
        }
        stack.push(new CacheFrame(key + "/", saved));
        try {
            LOGGER.log(Level.FINE, "using cache {0} / {1}: {2} file entries", new Object[] {container, key, saved.size()});
            return callable.call();
        } finally {
            LOGGER.log(Level.FINE, "exit cache {0} / {1}", new Object[] {container, key});
            stack.pop();
        }
    }

    private Deque<CacheFrame> cacheFrames() {
        return cache.get().computeIfAbsent(container, c -> new ArrayDeque<>());
    }

    /** Finds a cache frame whose {@link CacheFrame#root} is a prefix of the given {@link #key} or {@code /}-appended variant. */
    private @CheckForNull CacheFrame findCacheFrame(String key) {
        return cacheFrames().stream().filter(frame -> key.startsWith(frame.root)).findFirst().orElse(null);
    }

    /**
     * Delete all blobs starting with a given prefix.
     */
    public static boolean delete(BlobStoreProvider provider, String prefix) throws IOException, InterruptedException {
        try {
            List<ObjectIdentifier> identifiers = new ArrayList<>();
            for (ObjectIdentifier sm : provider.listAll(provider.getContainer(), prefix)) {
                String path = sm.key();
                if (!path.startsWith(prefix)) {
                    LOGGER.warning(() -> path + " does not start with " + prefix);
                    continue;
                }
                identifiers.add(sm);
            }
            if (identifiers.isEmpty()) {
                LOGGER.log(Level.FINE, "nothing to delete under {0}", prefix);
                return false;
            } else {
                LOGGER.log(Level.FINE, "deleting {0} blobs under {1}", new Object[] {identifiers.size(), prefix});
                provider.delete(provider.getContainer(), identifiers);
                return true;
            }
        } catch (RuntimeException x) {
            throw new IOException(x);
        }
    }

}
