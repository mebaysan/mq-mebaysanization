package com.baysansoft.mqmanager.support;

import org.apache.activemq.broker.BrokerService;

/**
 * Developer utility: starts a throwaway ActiveMQ Classic broker on a fixed port so the UI can be driven
 * by hand without installing anything.
 *
 * <pre>
 * mvn -q dependency:build-classpath -Dmdep.outputFile=target/test-cp.txt -Dmdep.includeScope=test
 * java -cp "target/classes:target/test-classes:$(cat target/test-cp.txt)" \
 *      com.baysansoft.mqmanager.support.LocalBrokerLauncher [port]
 * </pre>
 *
 * <p>Not a test. Nothing is persisted: the broker is in-memory and everything disappears on exit.
 */
public final class LocalBrokerLauncher {

    private LocalBrokerLauncher() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 61616;

        BrokerService broker = new BrokerService();
        broker.setBrokerName("mqm-local");
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.setAdvisorySupport(false);
        broker.addConnector("tcp://127.0.0.1:" + port);
        broker.start();
        broker.waitUntilStarted();

        System.out.println("Local ActiveMQ Classic broker listening on tcp://127.0.0.1:" + port);
        System.out.println("Press Ctrl-C to stop.");
        Thread.currentThread().join();
    }
}
