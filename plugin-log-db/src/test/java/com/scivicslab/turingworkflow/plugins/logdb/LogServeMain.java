package com.scivicslab.turingworkflow.plugins.logdb;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

/**
 * Starts log-serve as its own program, with the options the command registers, so that a test can run it in a
 * JVM of its own. H2 reads its bind address once, when its classes are first loaded, so the answer to "which
 * interfaces does it listen on" can only be seen from a JVM that has not loaded H2 yet.
 */
public final class LogServeMain {

    private LogServeMain() {
    }

    public static void main(String[] args) throws Exception {
        Options opts = new Options();
        opts.addOption(Option.builder().longOpt("db").hasArg(true).build());
        opts.addOption(Option.builder().longOpt("http-port").hasArg(true).build());
        opts.addOption(Option.builder().longOpt("tcp-port").hasArg(true).build());
        opts.addOption(Option.builder().longOpt("no-tcp").build());
        opts.addOption(Option.builder().longOpt("bind").hasArg(true).build());
        opts.addOption(Option.builder().longOpt("tls-keystore").hasArg(true).build());
        opts.addOption(Option.builder().longOpt("tls-keystore-password-env").hasArg(true).build());
        opts.addOption(Option.builder().longOpt("tls-truststore").hasArg(true).build());
        opts.addOption(Option.builder().longOpt("tls-truststore-password-env").hasArg(true).build());
        CommandLine cl = new DefaultParser().parse(opts, args);
        new LogServeCLI().execute(cl);
    }
}
