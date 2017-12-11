package ru.nsu.ccfit.bogush.net.forwarder;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;

public class PortForwarder {
    private static void usage() {
        System.out.print(
                "Usage\n\n\t" +

                "<program_name> lport rhost rport\n\n" +

                "Description\n\n\t" +

                "A port forwarder is a program which lives between client\n\t" +
                "connecting to some internet service and the service itself.\n\t" +
                "It takes incoming bytes on from local tcp connection and\n\t" +
                "forwards them to the requested service connection pretending\n\t" +
                "the client\n\n" +

                "Arguments\n\n\t" +

                "lport - the port on which the forwarder works.\n\t" +
                "It is a port which the client passes to address line \n\t" +
                "(e.g. 'http://google.com:12345', the lport is 12345)\n\n\t" +

                "rhost and rport together describe the requested service.\n\n");
    }

    private static final int REQUIRED_NUMBER_OF_ARGUMENTS = 3;
    private static final int LPORT_ARGUMENT_INDEX = 0;
    private static final int RHOST_ARGUMENT_INDEX = 1;
    private static final int RPORT_ARGUMENT_INDEX = 2;
    private static final int EXIT_FAILURE = 1;

    public static void main(String[] args) {
        if (args.length != REQUIRED_NUMBER_OF_ARGUMENTS) {
            usage();
            System.exit(EXIT_FAILURE);
        }

        int lport = 0;

        try {
            lport = Integer.parseInt(args[LPORT_ARGUMENT_INDEX]);
        } catch (NumberFormatException e) {
            System.err.println("lport must be a number");
            e.printStackTrace();
            usage();
            System.exit(EXIT_FAILURE);
            // TODO: 12/10/17 correctly handle exception
        }

        String rhostString = args[RHOST_ARGUMENT_INDEX];
        InetAddress rhost = null;
        try {
            rhost = InetAddress.getByName(rhostString);
        } catch (UnknownHostException e) {
            System.err.println("couldn't resolve rhost '" + rhostString + "'");
            e.printStackTrace();
            usage();
            System.exit(EXIT_FAILURE);
            // TODO: 12/10/17 correctly handle exception
        }

        int rport = 0;

        try {
            rport = Integer.parseInt(args[RPORT_ARGUMENT_INDEX]);
        } catch (NumberFormatException e) {
            System.err.println("rport must be a number");
            e.printStackTrace();
            usage();
            System.exit(EXIT_FAILURE);
            // TODO: 12/10/17 correctly handle exception
        }

        PortForwarder portForwarder = null;

        try {
            portForwarder = new PortForwarder(lport, rhost, rport);
        } catch (IOException e) {
            e.printStackTrace();
            // TODO: 12/10/17 correctly handle exception
            System.exit(EXIT_FAILURE);
        }

        portForwarder.start();
    }

    private final Selector selector;
    private final ServerSocketChannel serverSocketChannel;
    private final InetSocketAddress localSocketAddress;
    private final InetSocketAddress remoteSocketAddress;
    private static final int BUFF_SIZE = 1 << 20; // 1 megabyte

    private PortForwarder(int lport, InetAddress rhost, int rport) throws IOException {
        System.out.format("Configure Port Forwarder with params:\n\t" +
                "lport: %s\n\t" +
                "rhost: %s\n\t" +
                "rport: %s\n\n", lport, rhost, rport);

        selector = Selector.open();
        serverSocketChannel = ServerSocketChannel.open();
        localSocketAddress = new InetSocketAddress(lport);
        remoteSocketAddress = new InetSocketAddress(rhost, rport);

        System.out.format("Configured Port Forwarder\n\t" +
                "local: %s\n\tremote: %s\n\n", localSocketAddress, remoteSocketAddress);

        if (remoteSocketAddress.isUnresolved()) {
            System.err.println("Remote socket address is unresolved :(");
        }
    }

    private void start() {
        try {
            System.out.println("Starting Port Forwarder...");
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.bind(localSocketAddress);
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT, serverSocketChannel);
            System.out.format("Started Port Forwarder:\n\t" +
                    "local: %s\n\tremote: %s\n\n", serverSocketChannel.getLocalAddress(), remoteSocketAddress);
        } catch (IOException e) {
            System.err.println("Start failed :(");
            e.printStackTrace();
            return;
        }

        try {
            loop();
        } catch (IOException e) {
            System.err.println("Loop failed :(");
            e.printStackTrace();
        }
    }

    private void loop() throws IOException {
        while (!Thread.interrupted()) {
            int numberReady = 0;
            while (numberReady <= 0) {
                numberReady = selector.select();
            }

            System.out.println(numberReady + " channels are ready");
            System.out.println("processing them...\n");
            int cnt = 0;
            Iterator<SelectionKey> keyIter = selector.selectedKeys().iterator();
            while (keyIter.hasNext()) {
                SelectionKey key = keyIter.next();
                if (!key.isValid()) {
                    keyIter.remove();
                    continue;
                }
                try {
                    System.out.format("%d. %s\n", ++cnt, key.channel());

                    if (key.isAcceptable()) {
                        System.out.println("\tis acceptable");
                        acceptNewConnection();
                        continue;
                    } else if (key.isConnectable()) {
                        System.out.println("\tis connectable");
                        continue;
                    }

                    if (key.isReadable()) {
                        System.out.println("\tis readable");
                        ForwardUnit forwardUnit = (ForwardUnit) key.attachment();
                        int bytesRead = forwardUnit.read();
                        if (bytesRead < 0) {
                            // eof
                            forwardUnit.in.close();
                            forwardUnit.out.close();
                            continue;
                        }
                        System.out.format("\tbytes read: %d\n", bytesRead);
                    }

                    if (key.isWritable()) {
                        System.out.println("\tis writable");
                        ForwardUnit forwardUnit = (ForwardUnit) key.attachment();
                        int bytesWritten = forwardUnit.write();
                        System.out.format("\tbytes written: %d\n", bytesWritten);
                    }
                } finally {
                    keyIter.remove();
                }

                System.out.println();
            }
        }
    }

    private void acceptNewConnection() throws IOException {
        SocketChannel localChannel;
        try {
            localChannel = serverSocketChannel.accept();
            localChannel.configureBlocking(false);
            System.out.println("\taccepted " + localChannel);
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (IOException e) {
            e.printStackTrace();
            // TODO: 12/10/17 correctly handle exception
            throw e;
        }

        SocketChannel remoteChannel = null;

        try {
//            System.out.println("\tconnecting it to remote: " + remoteSocketAddress);
            remoteChannel = SocketChannel.open();
            remoteChannel.connect(remoteSocketAddress);
            remoteChannel.configureBlocking(false);
            System.out.println("\tconnected " + remoteChannel);
        } catch (IOException e) {
            e.printStackTrace();
            try {
                localChannel.close();
                if (remoteChannel != null) {
                    remoteChannel.close();
                }
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            // TODO: 12/10/17 correctly handle exception
            throw e;
        }

        try {
            ForwardUnit l2r = new ForwardUnit(localChannel, remoteChannel, BUFF_SIZE);
            ForwardUnit r2l = new ForwardUnit(remoteChannel, localChannel, BUFF_SIZE);
            localChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, l2r);
            remoteChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, r2l);
        } catch (IOException e) {
            e.printStackTrace();
            throw e;
            // TODO: 12/10/17 correctly handle exception
        }
    }

    private static class ForwardUnit {
        private final SocketChannel in;
        private final SocketChannel out;
        private final ByteBuffer buffer;
        private boolean lastOperationWasRead = true;

        private ForwardUnit(SocketChannel in, SocketChannel out, int bufferSize) {
            this.in = in;
            this.out = out;
            this.buffer = ByteBuffer.allocate(bufferSize);
        }

        private int read() throws IOException {
            if (!lastOperationWasRead) buffer.compact();
            int bytesRead = in.read(buffer);
            lastOperationWasRead = true;
            return bytesRead;
        }

        private int write() throws IOException {
            if (lastOperationWasRead) buffer.flip();
            int bytesWritten = out.write(buffer);
            lastOperationWasRead = false;
            return bytesWritten;
        }
    }
}
