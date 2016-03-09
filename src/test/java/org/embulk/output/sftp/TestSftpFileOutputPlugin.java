package org.embulk.output.sftp;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.scp.ScpCommandFactory;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.server.subsystem.sftp.SftpSubsystemFactory;
import org.embulk.EmbulkTestRuntime;
import org.embulk.config.ConfigException;
import org.embulk.config.ConfigLoader;
import org.embulk.config.ConfigSource;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.output.sftp.SftpFileOutputPlugin.PluginTask;
import org.embulk.spi.Exec;
import org.embulk.spi.FileOutputRunner;
import org.embulk.spi.OutputPlugin.Control;
import org.embulk.spi.Page;
import org.embulk.spi.PageTestUtils;
import org.embulk.spi.Schema;
import org.embulk.spi.TransactionalPageOutput;
import org.embulk.spi.time.Timestamp;
import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PublicKey;
import java.util.Collections;
import java.util.List;

import static com.google.common.io.Files.readLines;
import static org.embulk.spi.type.Types.BOOLEAN;
import static org.embulk.spi.type.Types.DOUBLE;
import static org.embulk.spi.type.Types.JSON;
import static org.embulk.spi.type.Types.LONG;
import static org.embulk.spi.type.Types.STRING;
import static org.embulk.spi.type.Types.TIMESTAMP;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.msgpack.value.ValueFactory.newMap;
import static org.msgpack.value.ValueFactory.newString;

public class TestSftpFileOutputPlugin
{
    @Rule
    public EmbulkTestRuntime runtime = new EmbulkTestRuntime();

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Rule
    public TemporaryFolder testFolder = new TemporaryFolder();

    private Logger logger = runtime.getExec().getLogger(TestSftpFileOutputPlugin.class);
    private FileOutputRunner runner;
    private SshServer sshServer;
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 20022;
    private static final String PROXY_HOST = "127.0.0.1";
    private static final int PROXY_PORT = 8080;
    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";
    private static final String SECRET_KEY_FILE = Resources.getResource("id_rsa").getPath();
    private static final String SECRET_KEY_PASSPHRASE = "SECRET_KEY_PASSPHRASE";
    private static final Schema SCHEMA = new Schema.Builder()
            .add("_c0", BOOLEAN)
            .add("_c1", LONG)
            .add("_c2", DOUBLE)
            .add("_c3", STRING)
            .add("_c4", TIMESTAMP)
            .add("_c5", JSON)
            .build();

    @Before
    public void createResources()
            throws IOException
    {
        // setup the plugin
        SftpFileOutputPlugin sftpFileOutputPlugin = new SftpFileOutputPlugin();
        runner = new FileOutputRunner(sftpFileOutputPlugin);

        sshServer = createSshServer(HOST, PORT, USERNAME, PASSWORD);
    }

    private SshServer createSshServer(String host, int port, final String sshUsername, final String sshPassword)
    {
        // setup a mock sftp server
        SshServer sshServer = SshServer.setUpDefaultServer();
        VirtualFileSystemFactory fsFactory = new VirtualFileSystemFactory();
        fsFactory.setUserHomeDir(sshUsername, testFolder.getRoot().toPath());
        sshServer.setFileSystemFactory(fsFactory);
        sshServer.setHost(host);
        sshServer.setPort(port);
        sshServer.setSubsystemFactories(Collections.<NamedFactory<Command>>singletonList(new SftpSubsystemFactory()));
        sshServer.setCommandFactory(new ScpCommandFactory());
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        sshServer.setPasswordAuthenticator(new PasswordAuthenticator()
        {
            @Override
            public boolean authenticate(final String username, final String password, final ServerSession session)
            {
                return sshUsername.contentEquals(username) && sshPassword.contentEquals(password);
            }
        });
        sshServer.setPublickeyAuthenticator(new PublickeyAuthenticator()
        {
            @Override
            public boolean authenticate(String username, PublicKey key, ServerSession session)
            {
                return true;
            }
        });

        try {
            sshServer.start();
        }
        catch (IOException e) {
            logger.debug(e.getMessage(), e);
        }
        return sshServer;
    }

    private HttpProxyServer createProxyServer(int port)
    {
        return DefaultHttpProxyServer.bootstrap()
                .withPort(port)
                .start();
    }

    @After
    public void cleanup() throws InterruptedException
    {
        try {
            sshServer.stop(true);
        }
        catch (Exception e) {
            logger.debug(e.getMessage(), e);
        }
    }

    private ConfigSource getConfigFromYaml(String yaml)
    {
        ConfigLoader loader = new ConfigLoader(Exec.getModelManager());
        return loader.fromYamlString(yaml);
    }

    private List<String> lsR(List<String> fileNames, Path dir)
    {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                if (path.toFile().isDirectory()) {
                    lsR(fileNames, path);
                }
                else {
                    fileNames.add(path.toAbsolutePath().toString());
                }
            }
        }
        catch (IOException e) {
            logger.debug(e.getMessage(), e);
        }
        return fileNames;
    }

    private void run(String configYaml, final Optional<Integer> sleep)
    {
        ConfigSource config = getConfigFromYaml(configYaml);
        runner.transaction(config, SCHEMA, 1, new Control()
        {
            @Override
            public List<TaskReport> run(TaskSource taskSource)
            {
                TransactionalPageOutput pageOutput = runner.open(taskSource, SCHEMA, 1);
                boolean committed = false;
                try {
                    // Result:
                    // _c0,_c1,_c2,_c3,_c4,_c5
                    // true,2,3.0,45,1970-01-01 00:00:00.678000 +0000,{\"k\":\"v\"}
                    // true,2,3.0,45,1970-01-01 00:00:00.678000 +0000,{\"k\":\"v\"}
                    for (Page page : PageTestUtils.buildPage(runtime.getBufferAllocator(), SCHEMA,
                                 true, 2L, 3.0D, "45", Timestamp.ofEpochMilli(678L), newMap(newString("k"), newString("v")),
                                 true, 2L, 3.0D, "45", Timestamp.ofEpochMilli(678L), newMap(newString("k"), newString("v")))) {
                        pageOutput.add(page);
                        if (sleep.isPresent()) {
                            Thread.sleep(sleep.get() * 1000);
                        }
                    }
                    pageOutput.commit();
                    committed = true;
                }
                catch (InterruptedException e) {
                    logger.debug(e.getMessage(), e);
                }
                finally {
                    if (!committed) {
                        pageOutput.abort();
                    }
                    pageOutput.close();
                }
                return Lists.newArrayList();
            }
        });
    }

    private void assertRecordsInFile(String filePath)
    {
        try {
            List<String> lines = readLines(new File(filePath),
                                           Charsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String[] record = lines.get(i).split(",");
                if (i == 0) {
                    for (int j = 0; j <= 4; j++) {
                        assertEquals("_c" + j, record[j]);
                    }
                }
                else {
                    // true,2,3.0,45,1970-01-01 00:00:00.678000 +0000
                    assertEquals("true", record[0]);
                    assertEquals("2", record[1]);
                    assertEquals("3.0", record[2]);
                    assertEquals("45", record[3]);
                    assertEquals("1970-01-01 00:00:00.678000 +0000", record[4]);
                    assertEquals("{\"k\":\"v\"}", record[5]);
                }
            }
        }
        catch (IOException e) {
            logger.debug(e.getMessage(), e);
        }
    }

    @Test
    public void testConfigValuesIncludingDefault()
    {
        // setting embulk config
        final String pathPrefix = "/test/testUserPassword";
        String configYaml = "" +
                "type: sftp\n" +
                "host: " + HOST + "\n" +
                "user: " + USERNAME + "\n" +
                "path_prefix: " + pathPrefix + "\n" +
                "file_ext: txt\n" +
                "formatter:\n" +
                "  type: csv\n" +
                "  newline: CRLF\n" +
                "  newline_in_field: LF\n" +
                "  header_line: true\n" +
                "  charset: UTF-8\n" +
                "  quote_policy: NONE\n" +
                "  quote: \"\\\"\"\n" +
                "  escape: \"\\\\\"\n" +
                "  null_string: \"\"\n" +
                "  default_timezone: 'UTC'";

        ConfigSource config = getConfigFromYaml(configYaml);
        PluginTask task = config.loadConfig(PluginTask.class);

        assertEquals(HOST, task.getHost());
        assertEquals(22, task.getPort());
        assertEquals(USERNAME, task.getUser());
        assertEquals(Optional.absent(), task.getPassword());
        assertEquals(Optional.absent(), task.getSecretKeyFilePath());
        assertEquals("", task.getSecretKeyPassphrase());
        assertEquals(true, task.getUserDirIsRoot());
        assertEquals(600, task.getSftpConnectionTimeout());
        assertEquals(5, task.getMaxConnectionRetry());
        assertEquals(pathPrefix, task.getPathPrefix());
        assertEquals("txt", task.getFileNameExtension());
        assertEquals("%03d.%02d.", task.getSequenceFormat());
        assertEquals(Optional.absent(), task.getProxy());
    }

    @Test
    public void testConfigValuesIncludingProxy()
    {
        // setting embulk config
        final String pathPrefix = "/test/testUserPassword";
        String configYaml = "" +
                "type: sftp\n" +
                "host: " + HOST + "\n" +
                "user: " + USERNAME + "\n" +
                "path_prefix: " + pathPrefix + "\n" +
                "file_ext: txt\n" +
                "proxy: \n" +
                "  type: http\n" +
                "  host: proxy_host\n" +
                "  port: 80 \n" +
                "  user: proxy_user\n" +
                "  password: proxy_pass\n" +
                "  command: proxy_command\n" +
                "formatter:\n" +
                "  type: csv\n" +
                "  newline: CRLF\n" +
                "  newline_in_field: LF\n" +
                "  header_line: true\n" +
                "  charset: UTF-8\n" +
                "  quote_policy: NONE\n" +
                "  quote: \"\\\"\"\n" +
                "  escape: \"\\\\\"\n" +
                "  null_string: \"\"\n" +
                "  default_timezone: 'UTC'";

        ConfigSource config = getConfigFromYaml(configYaml);
        PluginTask task = config.loadConfig(PluginTask.class);

        ProxyTask proxy = task.getProxy().get();
        assertEquals("proxy_command", proxy.getCommand().get());
        assertEquals("proxy_host", proxy.getHost().get());
        assertEquals("proxy_user", proxy.getUser().get());
        assertEquals("proxy_pass", proxy.getPassword().get());
        assertEquals(80, proxy.getPort());
        assertEquals(ProxyTask.ProxyType.HTTP, proxy.getType());
    }

    // Cases
    //   login(all cases needs host + port)
    //     user + password
    //     user + secret_key_file + secret_key_passphrase
    //   put files
    //     user_directory_is_root
    //     not user_directory_is_root
    //   timeout
    //     0 second

    @Test
    public void testUserPasswordAndPutToUserDirectoryRoot()
    {
        // setting embulk config
        final String pathPrefix = "/test/testUserPassword";
        String configYaml = "" +
                "type: sftp\n" +
                "host: " + HOST + "\n" +
                "port: " + PORT + "\n" +
                "user: " + USERNAME + "\n" +
                "password: " + PASSWORD + "\n" +
                "path_prefix: " + pathPrefix + "\n" +
                "file_ext: txt\n" +
                "formatter:\n" +
                "  type: csv\n" +
                "  newline: CRLF\n" +
                "  newline_in_field: LF\n" +
                "  header_line: true\n" +
                "  charset: UTF-8\n" +
                "  quote_policy: NONE\n" +
                "  quote: \"\\\"\"\n" +
                "  escape: \"\\\\\"\n" +
                "  null_string: \"\"\n" +
                "  default_timezone: 'UTC'";

        // runner.transaction -> ...
        run(configYaml, Optional.<Integer>absent());

        List<String> fileList = lsR(Lists.<String>newArrayList(), Paths.get(testFolder.getRoot().getAbsolutePath()));
        assertThat(fileList, hasItem(containsString(pathPrefix + "001.00.txt")));
        assertRecordsInFile(String.format("%s/%s001.00.txt",
                                          testFolder.getRoot().getAbsolutePath(),
                                          pathPrefix));
    }

    @Test
    public void testUserSecretKeyFileAndPutToRootDirectory()
    {
        // setting embulk config
        final String pathPrefix = "/test/testUserPassword";
        String configYaml = "" +
                "type: sftp\n" +
                "host: " + HOST + "\n" +
                "port: " + PORT + "\n" +
                "user: " + USERNAME + "\n" +
                "secret_key_file: " + SECRET_KEY_FILE + "\n" +
                "secret_key_passphrase: " + SECRET_KEY_PASSPHRASE + "\n" +
                "path_prefix: " + testFolder.getRoot().getAbsolutePath() + pathPrefix + "\n" +
                "file_ext: txt\n" +
                "formatter:\n" +
                "  type: csv\n" +
                "  newline: CRLF\n" +
                "  newline_in_field: LF\n" +
                "  header_line: true\n" +
                "  charset: UTF-8\n" +
                "  quote_policy: NONE\n" +
                "  quote: \"\\\"\"\n" +
                "  escape: \"\\\\\"\n" +
                "  null_string: \"\"\n" +
                "  default_timezone: 'UTC'";

        // runner.transaction -> ...
        run(configYaml, Optional.<Integer>absent());

        List<String> fileList = lsR(Lists.<String>newArrayList(), Paths.get(testFolder.getRoot().getAbsolutePath()));
        assertThat(fileList, hasItem(containsString(pathPrefix + "001.00.txt")));

        assertRecordsInFile(String.format("%s/%s001.00.txt",
                                          testFolder.getRoot().getAbsolutePath(),
                                          pathPrefix));
    }

    @Test
    public void testUserSecretKeyFileWithProxy()
    {
        HttpProxyServer proxyServer = null;
        try {
             proxyServer = createProxyServer(PROXY_PORT);

            // setting embulk config
            final String pathPrefix = "/test/testUserPassword";
            String configYaml = "" +
                    "type: sftp\n" +
                    "host: " + HOST + "\n" +
                    "port: " + PORT + "\n" +
                    "user: " + USERNAME + "\n" +
                    "secret_key_file: " + SECRET_KEY_FILE + "\n" +
                    "secret_key_passphrase: " + SECRET_KEY_PASSPHRASE + "\n" +
                    "path_prefix: " + testFolder.getRoot().getAbsolutePath() + pathPrefix + "\n" +
                    "file_ext: txt\n" +
                    "proxy: \n" +
                    "  type: http\n" +
                    "  host: " + PROXY_HOST + "\n" +
                    "  port: " + PROXY_PORT + " \n" +
                    "  user: " + USERNAME + "\n" +
                    "  password: " + PASSWORD + "\n" +
                    "  command: \n" +
                    "formatter:\n" +
                    "  type: csv\n" +
                    "  newline: CRLF\n" +
                    "  newline_in_field: LF\n" +
                    "  header_line: true\n" +
                    "  charset: UTF-8\n" +
                    "  quote_policy: NONE\n" +
                    "  quote: \"\\\"\"\n" +
                    "  escape: \"\\\\\"\n" +
                    "  null_string: \"\"\n" +
                    "  default_timezone: 'UTC'";

            // runner.transaction -> ...
            run(configYaml, Optional.<Integer>absent());

            List<String> fileList = lsR(Lists.<String>newArrayList(), Paths.get(testFolder.getRoot().getAbsolutePath()));
            assertThat(fileList, hasItem(containsString(pathPrefix + "001.00.txt")));

            assertRecordsInFile(String.format("%s/%s001.00.txt",
                    testFolder.getRoot().getAbsolutePath(),
                    pathPrefix));
        }
        finally {
            if (proxyServer != null) {
                proxyServer.stop();
            }
        }
    }

    @Test
    public void testTimeout()
    {
        // setting embulk config
        final String pathPrefix = "/test/testUserPassword";
        String configYaml = "" +
                "type: sftp\n" +
                "host: " + HOST + "\n" +
                "port: " + PORT + "\n" +
                "user: " + USERNAME + "\n" +
                "secret_key_file: " + SECRET_KEY_FILE + "\n" +
                "secret_key_passphrase: " + SECRET_KEY_PASSPHRASE + "\n" +
                "path_prefix: " + testFolder.getRoot().getAbsolutePath() + pathPrefix + "\n" +
                "timeout: 1\n" +
                "file_ext: txt\n" +
                "formatter:\n" +
                "  type: csv\n" +
                "  newline: CRLF\n" +
                "  newline_in_field: LF\n" +
                "  header_line: true\n" +
                "  charset: UTF-8\n" +
                "  quote_policy: NONE\n" +
                "  quote: \"\\\"\"\n" +
                "  escape: \"\\\\\"\n" +
                "  null_string: \"\"\n" +
                "  default_timezone: 'UTC'";

        // exception
        exception.expect(RuntimeException.class);
        exception.expectCause(CoreMatchers.<Throwable>instanceOf(FileSystemException.class));
        exception.expectMessage("Could not connect to SFTP server");

        // runner.transaction -> ...
        run(configYaml, Optional.of(60)); // sleep 1 minute while processing
    }

    @Test
    public void testProxyType()
    {
        // test valueOf()
        assertEquals("http", ProxyTask.ProxyType.valueOf("HTTP").toString());
        assertEquals("socks", ProxyTask.ProxyType.valueOf("SOCKS").toString());
        assertEquals("stream", ProxyTask.ProxyType.valueOf("STREAM").toString());
        try {
            ProxyTask.ProxyType.valueOf("non-existing-type");
        }
        catch (Exception e) {
            assertEquals(IllegalArgumentException.class, e.getClass());
        }

        // test fromString
        assertEquals(ProxyTask.ProxyType.HTTP, ProxyTask.ProxyType.fromString("http"));
        assertEquals(ProxyTask.ProxyType.SOCKS, ProxyTask.ProxyType.fromString("socks"));
        assertEquals(ProxyTask.ProxyType.STREAM, ProxyTask.ProxyType.fromString("stream"));
        try {
            ProxyTask.ProxyType.fromString("non-existing-type");
        }
        catch (Exception e) {
            assertEquals(ConfigException.class, e.getClass());
        }
    }
}
