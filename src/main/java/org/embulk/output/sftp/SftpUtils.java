package org.embulk.output.sftp;

import com.google.common.base.Function;
import com.google.common.base.Throwables;
import org.apache.commons.io.IOUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.apache.commons.vfs2.provider.sftp.IdentityInfo;
import org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;
import org.embulk.config.ConfigException;
import org.embulk.spi.Exec;
import org.embulk.spi.unit.LocalFile;
import org.embulk.spi.util.RetryExecutor.RetryGiveupException;
import org.embulk.spi.util.RetryExecutor.Retryable;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import static org.embulk.output.sftp.SftpFileOutputPlugin.PluginTask;
import static org.embulk.spi.util.RetryExecutor.retryExecutor;

/**
 * Created by takahiro.nakayama on 10/20/15.
 */
public class SftpUtils
{
    private final Logger logger = Exec.getLogger(SftpUtils.class);
    private final StandardFileSystemManager manager;
    private final FileSystemOptions fsOptions;
    private final String userInfo;
    private final String host;
    private final int port;
    private final int maxConnectionRetry;

    private StandardFileSystemManager initializeStandardFileSystemManager()
    {
        if (!logger.isDebugEnabled()) {
            // TODO: change logging format: org.apache.commons.logging.Log
            System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");
        }
        StandardFileSystemManager manager = new StandardFileSystemManager();
        manager.setClassLoader(SftpUtils.class.getClassLoader());
        try {
            manager.init();
        }
        catch (FileSystemException e) {
            logger.error(e.getMessage());
            throw new ConfigException(e);
        }

        return manager;
    }

    private String initializeUserInfo(PluginTask task)
    {
        String userInfo = task.getUser();
        if (task.getPassword().isPresent()) {
            userInfo += ":" + task.getPassword().get();
        }
        return userInfo;
    }

    private FileSystemOptions initializeFsOptions(PluginTask task)
    {
        FileSystemOptions fsOptions = new FileSystemOptions();

        try {
            SftpFileSystemConfigBuilder builder = SftpFileSystemConfigBuilder.getInstance();
            builder.setUserDirIsRoot(fsOptions, task.getUserDirIsRoot());
            builder.setTimeout(fsOptions, task.getSftpConnectionTimeout() * 1000);
            builder.setStrictHostKeyChecking(fsOptions, "no");
            if (task.getSecretKeyFilePath().isPresent()) {
                IdentityInfo identityInfo = new IdentityInfo(
                    new File((task.getSecretKeyFilePath().transform(localFileToPathString()).get())),
                    task.getSecretKeyPassphrase().getBytes()
                );
                builder.setIdentityInfo(fsOptions, identityInfo);
                logger.info("set identity: {}", task.getSecretKeyFilePath().get());
            }

            if (task.getProxy().isPresent()) {
                ProxyTask proxy = task.getProxy().get();

                ProxyTask.ProxyType.setProxyType(builder, fsOptions, proxy.getType());

                if (proxy.getHost().isPresent()) {
                    builder.setProxyHost(fsOptions, proxy.getHost().get());
                    builder.setProxyPort(fsOptions, proxy.getPort());
                }

                if (proxy.getUser().isPresent()) {
                    builder.setProxyUser(fsOptions, proxy.getUser().get());
                }

                if (proxy.getPassword().isPresent()) {
                    builder.setProxyPassword(fsOptions, proxy.getPassword().get());
                }

                if (proxy.getCommand().isPresent()) {
                    builder.setProxyCommand(fsOptions, proxy.getCommand().get());
                }
            }
        }
        catch (FileSystemException e) {
            logger.error(e.getMessage());
            throw new ConfigException(e);
        }

        return fsOptions;
    }

    SftpUtils(PluginTask task)
    {
        this.manager = initializeStandardFileSystemManager();
        this.userInfo = initializeUserInfo(task);
        this.fsOptions = initializeFsOptions(task);
        this.host = task.getHost();
        this.port = task.getPort();
        this.maxConnectionRetry = task.getMaxConnectionRetry();
    }

    public void close()
    {
        manager.close();
    }

    public Void uploadFile(final File localTempFile, final String remotePath)
    {
        try {
            return retryExecutor()
                    .withRetryLimit(maxConnectionRetry)
                    .withInitialRetryWait(500)
                    .withMaxRetryWait(30 * 1000)
                    .runInterruptible(new Retryable<Void>() {
                        @Override
                        public Void call() throws IOException
                        {
                            try (FileObject remoteFile = newSftpFile(getSftpFileUri(remotePath));
                                 BufferedOutputStream outputStream = new BufferedOutputStream(remoteFile.getContent().getOutputStream());
                                 BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(localTempFile))
                            ) {
                                logger.info("new sftp file: {}", remoteFile.getPublicURIString());
                                IOUtils.copy(inputStream, outputStream);
                            }
                            return null;
                        }

                        @Override
                        public boolean isRetryableException(Exception exception)
                        {
                            return true;
                        }

                        @Override
                        public void onRetry(Exception exception, int retryCount, int retryLimit, int retryWait) throws RetryGiveupException
                        {
                            String message = String.format("SFTP output failed. Retrying %d/%d after %d seconds. Message: %s",
                                    retryCount, retryLimit, retryWait / 1000, exception.getMessage());
                            if (retryCount % 3 == 0) {
                                logger.warn(message, exception);
                            }
                            else {
                                logger.warn(message);
                            }
                        }

                        @Override
                        public void onGiveup(Exception firstException, Exception lastException) throws RetryGiveupException
                        {
                        }
                    });
        }
        catch (RetryGiveupException ex) {
            throw Throwables.propagate(ex.getCause());
        }
        catch (InterruptedException ex) {
            throw Throwables.propagate(ex);
        }
    }

    public Void renameFile(final String before, final String after)
    {
        try {
            return retryExecutor()
                    .withRetryLimit(maxConnectionRetry)
                    .withInitialRetryWait(500)
                    .withMaxRetryWait(30 * 1000)
                    .runInterruptible(new Retryable<Void>() {
                        @Override
                        public Void call() throws IOException
                        {
                            FileObject previousFile = manager.resolveFile(getSftpFileUri(before).toString(), fsOptions);
                            FileObject afterFile = manager.resolveFile(getSftpFileUri(after).toString(), fsOptions);
                            previousFile.moveTo(afterFile);
                            logger.info("renamed remoted file: {} to {}", previousFile.getPublicURIString(), afterFile.getPublicURIString());

                            return null;
                        }

                        @Override
                        public boolean isRetryableException(Exception exception)
                        {
                            return true;
                        }

                        @Override
                        public void onRetry(Exception exception, int retryCount, int retryLimit, int retryWait) throws RetryGiveupException
                        {
                            String message = String.format("SFTP rename remote file failed. Retrying %d/%d after %d seconds. Message: %s",
                                    retryCount, retryLimit, retryWait / 1000, exception.getMessage());
                            if (retryCount % 3 == 0) {
                                logger.warn(message, exception);
                            }
                            else {
                                logger.warn(message);
                            }
                        }

                        @Override
                        public void onGiveup(Exception firstException, Exception lastException) throws RetryGiveupException
                        {
                        }
                    });
        }
        catch (RetryGiveupException ex) {
            throw Throwables.propagate(ex.getCause());
        }
        catch (InterruptedException ex) {
            throw Throwables.propagate(ex);
        }
    }

    private URI getSftpFileUri(String remoteFilePath)
    {
        try {
            return new URI("sftp", userInfo, host, port, remoteFilePath, null, null);
        }
        catch (URISyntaxException e) {
            logger.error(e.getMessage());
            throw new ConfigException(e);
        }
    }

    private FileObject newSftpFile(final URI sftpUri) throws FileSystemException
    {
        FileObject file = manager.resolveFile(sftpUri.toString(), fsOptions);
        if (file.exists()) {
            file.delete();
        }
        if (file.getParent().exists()) {
            logger.info("parent directory {} exists there", file.getParent().getPublicURIString());
        }
        else {
            logger.info("trying to create parent directory {}", file.getParent().getPublicURIString());
            file.getParent().createFolder();
        }
        return file;
    }

    private Function<LocalFile, String> localFileToPathString()
    {
        return new Function<LocalFile, String>()
        {
            public String apply(LocalFile file)
            {
                return file.getPath().toString();
            }
        };
    }
}