0.2.6 (2021-02-02)
- Support OpenSsh.
0.2.5 (2020-08-10)
- Close unused Session.
  - https://github.com/embulk/embulk-output-sftp/pull/60
0.2.4 (2020-05-20)
- Add more log to monitor.
  - https://github.com/embulk/embulk-output-sftp/pull/58
0.2.2 (2019-07-26)
- Do not retry when "Connection refused" is returned and throw ConfigException
  - https://github.com/embulk/embulk-output-sftp/pull/56
0.2.1 (2018-10-23)
- Improved logic that detects exception is retryable or not
  - https://github.com/embulk/embulk-output-sftp/pull/52
0.2.0 (2018-08-29)
- Fix bug of stalling when closing `remoteFile`, this version is recommended over 0.1.11
  - https://github.com/embulk/embulk-output-sftp/pull/51
0.1.11 (2018-08-27)
- Enhance: Add 2 new configs `local_temp_file` (boolean) and `temp_file_threshold` (long)
  - https://github.com/embulk/embulk-output-sftp/pull/50
0.1.10 (2018-05-07)
- Fix: Use java.util.regex.Pattern for host name validation
  - https://github.com/embulk/embulk-output-sftp/pull/49
0.1.9 (2018-04-26)
- Enhance: Add validation for "host" and "proxy.host"
  - https://github.com/embulk/embulk-output-sftp/pull/48
0.1.8 (2018-03-20)
- Change input stream buffer size to 32MB to reduce number of storage reads
  - https://github.com/embulk/embulk-output-sftp/pull/47
0.1.7 (2018-03-14)
- Enhance: Upload with ".tmp" suffix and rename file name after upload 
  - https://github.com/embulk/embulk-output-sftp/pull/43
  - https://github.com/embulk/embulk-output-sftp/pull/44

0.1.6 (2018-03-13)
- Fix: Change input stream buffer size to 4MB to fix hanging issue
  - https://github.com/embulk/embulk-output-sftp/pull/46
0.1.5 (2018-03-13)
- Fix: Fix random hanging and log transfer progress
  - https://github.com/embulk/embulk-output-sftp/pull/45

0.1.4 (2017-12-21)
- Fix: Disable remote temporary file rename logic
  - https://github.com/embulk/embulk-output-sftp/pull/41
- Enhance: Upgrade "commons-vfs2", "com.jcraft:jsch" and "commons-io:commons-io"
  - https://github.com/embulk/embulk-output-sftp/pull/42

0.1.3 (2017-11-07)
- Enhance: Create temporary file and rename it after upload completed
  - https://github.com/embulk/embulk-output-sftp/pull/37

0.1.2 (2017-07-11)
- Fix: Increment fileIndex after complete uploadFile
  - https://github.com/embulk/embulk-output-sftp/pull/35

0.1.1 (2017-05-29)
- Fix: Improve retry logic
  - https://github.com/embulk/embulk-output-sftp/pull/34

0.0.9 (2017-03-09)
==================
- Fix: Hide password in the log
  - https://github.com/civitaspo/embulk-output-sftp/pull/25

0.0.8 (2016-09-26)
==================
- Fix: Use second as timetout setting instead of milli second
  - https://github.com/civitaspo/embulk-output-sftp/pull/22
- Fix: Fix CI failure only with Java7
  - https://github.com/civitaspo/embulk-output-sftp/pull/21
- Fix: Format code that were warned by `./gradlew checkstyle` command
  - https://github.com/civitaspo/embulk-output-sftp/pull/23

0.0.7 (2016-03-22)
==================
- Fix: Plugin throws ClassNotFoundException with EmbulkEmbed
  - https://github.com/civitaspo/embulk-output-sftp/pull/19

0.0.6 (2016-03-16)
==================
- Fix: Avoid some connection errors
  - https://github.com/civitaspo/embulk-output-sftp/pull/17

0.0.5 (2016-03-09)
==================
- Add: Support MapReduce executor
  - https://github.com/civitaspo/embulk-output-sftp/pull/8
- Fix: Use ConfigException instead of RuntimeException
  - https://github.com/civitaspo/embulk-output-sftp/pull/9/files
- Fix: Check to exist parent directory before uploading files
  - https://github.com/civitaspo/embulk-output-sftp/pull/13
- Add: Support proxy settings
  - https://github.com/civitaspo/embulk-output-sftp/pull/11
