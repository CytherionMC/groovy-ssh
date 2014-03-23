package org.hidetake.gradle.ssh.internal.operation

import groovy.transform.TupleConstructor
import groovy.util.logging.Slf4j
import org.codehaus.groovy.tools.Utilities
import org.hidetake.gradle.ssh.api.Remote
import org.hidetake.gradle.ssh.api.SshSettings
import org.hidetake.gradle.ssh.api.operation.ExecutionSettings
import org.hidetake.gradle.ssh.api.operation.Operations
import org.hidetake.gradle.ssh.api.operation.SftpHandler
import org.hidetake.gradle.ssh.api.operation.ShellSettings
import org.hidetake.gradle.ssh.api.ssh.Connection
import org.hidetake.gradle.ssh.registry.Registry

/**
 * Default implementation of {@link org.hidetake.gradle.ssh.api.operation.Operations}.
 *
 * @author hidetake.org
 */
@TupleConstructor
@Slf4j
class DefaultOperations implements Operations {
    final Connection connection
    final SshSettings sshSettings

    @Override
    Remote getRemote() {
        connection.remote
    }

    @Override
    void shell(ShellSettings settings, Closure closure) {
        def channel = connection.createShellChannel(settings)
        def context = ShellDelegate.create(channel, sshSettings.encoding)
        if (settings.logging) {
            context.enableLogging(sshSettings.outputLogLevel)
        }

        closure.delegate = context
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        closure()

        try {
            channel.connect()
            log.info("Channel #${channel.id} has been opened")
            while (!channel.closed) {
                sleep(100)
            }
            log.info("Channel #${channel.id} has been closed with exit status ${channel.exitStatus}")
            if (channel.exitStatus != 0) {
                throw new RuntimeException("Shell session finished with exit status ${channel.exitStatus}")
            }
        } finally {
            channel.disconnect()
        }
    }

    @Override
    String execute(ExecutionSettings settings, String command, Closure closure) {
        def channel = connection.createExecutionChannel(command, settings)
        def context = ExecutionDelegate.create(channel, sshSettings.encoding)
        if (settings.logging) {
            context.enableLogging(sshSettings.outputLogLevel, sshSettings.errorLogLevel)
        }

        def lines = [] as List<String>
        context.standardOutput.lineListeners.add { String line -> lines << line }

        closure.delegate = context
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        closure()

        try {
            channel.connect()
            log.info("Channel #${channel.id} has been opened")
            while (!channel.closed) {
                sleep(100)
            }
            log.info("Channel #${channel.id} has been closed with exit status ${channel.exitStatus}")
            if (channel.exitStatus != 0) {
                throw new RuntimeException(
                    "Command ($command) execution session finished with exit status ${channel.exitStatus}")
            }
            lines.join(Utilities.eol())
        } finally {
            channel.disconnect()
        }
    }

    @Override
    void executeBackground(ExecutionSettings settings, String command) {
        def channel = connection.createExecutionChannel(command, settings)
        def context = ExecutionDelegate.create(channel, sshSettings.encoding)
        if (settings.logging) {
            context.enableLogging(sshSettings.outputLogLevel, sshSettings.errorLogLevel)
        }

        channel.connect()
        log.info("Channel #${channel.id} has been opened")

        connection.whenClosed(channel) {
            log.info("Channel #${channel.id} has been closed with exit status ${channel.exitStatus}")
            channel.disconnect()
        }
    }

    @Override
    void sftp(Closure closure) {
        def channel = connection.createSftpChannel()
        try {
            channel.connect()
            log.info("SFTP Channel #${channel.id} has been opened")

            closure.delegate = Registry.instance[SftpHandler.Factory].create(channel)
            closure.resolveStrategy = Closure.DELEGATE_FIRST
            closure.call()

            log.info("SFTP Channel #${channel.id} has been closed")
        } finally {
            channel.disconnect()
        }
    }
}
