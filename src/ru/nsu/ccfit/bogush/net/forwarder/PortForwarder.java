package ru.nsu.ccfit.bogush.net.forwarder;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.Iterator;

import static java.nio.channels.SelectionKey.*;

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
    private final String rhost;
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
        this.rhost = rhost.getHostName();

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
            serverSocketChannel.register(selector, OP_ACCEPT, serverSocketChannel);
            System.out.format("Started Port Forwarder:\n\t" +
                    "local: %s\n\tremote: %s\n\n", serverSocketChannel.getLocalAddress(), remoteSocketAddress);
        } catch (IOException e) {
            System.err.println("Start failed :(");
            e.printStackTrace();
            return;
        }

        /*
         * after we've prepared our  port forwarder  to start
         * add line '127.0.0.1 <rhost>' to /etc/hosts to  let
         * the client start using the forwarder without doing
         * that ^ himself.
         */
        addHostEntry();

        try {
            loop();
        } catch (IOException e) {
            System.err.println("Loop failed :(");
            e.printStackTrace();
        }
    }

    private boolean hostEntryAdded = false;
    private static final String HOSTS_FILE_NAME = "/etc/hosts";
    private static final Path HOSTS_FILE_PATH = Paths.get(HOSTS_FILE_NAME);
    private Path hostsTmpPath;

    private void addHostEntry() {
        String hostEntry = "127.0.0.1 " + rhost;
        try {
            File tmp = File.createTempFile(HOSTS_FILE_NAME, ".tmp");
            hostsTmpPath = tmp.toPath();
            Files.copy(HOSTS_FILE_PATH, hostsTmpPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        try (FileWriter fileWriter = new FileWriter(HOSTS_FILE_NAME, true)) {
            System.out.format("Appending '%s' to %s...\t", hostEntry, HOSTS_FILE_NAME);
            BufferedWriter writer = new BufferedWriter(fileWriter);
            writer.write(hostEntry + System.lineSeparator());
            writer.close();
            System.out.println("done.");
            hostEntryAdded = true;
        } catch (IOException e) {
            System.err.format("Warning: Couldn't add host entry '%s': %s\n", hostEntry, e.getMessage());
            System.err.println("If this was due to lack of permissions you may rerun Port Forwarder\n" +
                    "as a super user (e.g. using sudo command, see man page sudo(1)).");
            System.err.println("Or you can do it manually before actually using Port Forwarder");
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(this::removeHostEntry, "HostEntryRemover"));
    }

    private void removeHostEntry() {
        if (!hostEntryAdded) return;

        try {
            Files.copy(hostsTmpPath, HOSTS_FILE_PATH, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(hostsTmpPath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loop() throws IOException {
        while (!Thread.interrupted()) {
            int numberReady = selector.select();
            while (numberReady <= 0) {
//                System.out.println("select returned " + numberReady);
//                System.out.println(selector.selectedKeys().size() + " keys are selected");
//                try {
//                    Thread.sleep(200);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
                numberReady = selector.select();
            }

            Iterator<SelectionKey> keyIter = selector.selectedKeys().iterator();
            while (keyIter.hasNext()) {
                SelectionKey key = keyIter.next();
                if (!key.isValid()) {
                    keyIter.remove();
                    continue;
                }

                try {
                    if (key.isAcceptable()) {
                        ChannelPair pair = accept();
                        System.out.format("ACCEPT\t%s\n", toString(pair.local));
                    } else if (key.isConnectable()) {
                        ChannelPair pair = (ChannelPair) key.attachment();
                        connect(pair);
                        System.out.format("CONNECT\t%s\n", toString(pair.remote));
                        continue;
                    }

                    if (key.isReadable()) {
                        System.out.format("READ from\t%s: ", toString(((SocketChannel) key.channel()).getRemoteAddress()));
                        ChannelPair pair = (ChannelPair) key.attachment();
                        boolean straight = key.channel() == pair.local;
                        int bytesRead = pair.read(straight);
//                        System.out.format("READ from %s: %d bytes\n",
//                                straight ? toString(pair.local) : toString(pair.remote), bytesRead);
                        System.out.format("%d bytes\n", bytesRead);
                        if (bytesRead == -1) continue;
                    }

                    if (key.isWritable()) {
                        System.out.format("WRITE to\t%s: ", toString(((SocketChannel) key.channel()).getRemoteAddress()));
                        ChannelPair pair = (ChannelPair) key.attachment();
                        boolean straight = key.channel() == pair.remote;
                        int bytesWritten = pair.write(straight);
//                        System.out.format("WRITE to %s: %d bytes\n",
//                                straight ? toString(pair.remote) : toString(pair.local), bytesWritten);
                        System.out.format("%d bytes\n", bytesWritten);
                    }
                } finally {
                    keyIter.remove();
//                    System.out.println();
                }
            }
        }
    }

    private ChannelPair accept() throws IOException {
        ChannelPair pair = new ChannelPair(BUFF_SIZE);

        try {
            pair.local = serverSocketChannel.accept();
            pair.local.configureBlocking(false);
//            System.out.println("accepted " + toString(pair.local));
            pair.local.register(selector, OP_READ, pair);

            pair.remote = SocketChannel.open();
            pair.remote.configureBlocking(false);
            pair.remote.connect(remoteSocketAddress);

            pair.remote.register(selector, OP_CONNECT, pair);
        } catch (IOException e) {
            e.printStackTrace();
            try {
                if (pair.local != null) {
                    pair.local.close();
                }

                if (pair.remote != null) {
                    pair.remote.close();
                }
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            // TODO: 12/10/17 correctly handle exception
            throw e;
        }

        return pair;
    }

    private void connect(ChannelPair pair) throws IOException {
        boolean connected;
        try {
            connected = pair.remote.finishConnect();
        } catch (IOException e) {
            e.printStackTrace();
            try {
                pair.remote.close();
                pair.local.close();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            throw e;
        }

        if (connected) {
//            System.out.println("connected " + toString(pair.remote));
            addOps(pair.remote.keyFor(selector), OP_READ);
        } else {
//            System.err.println("connect not finished");
        }
    }

    private void addOps(SelectionKey key, int ops) {
        key.interestOps(key.interestOps() | ops);
    }

    private void removeOps(SelectionKey key, int ops) {
        key.interestOps(key.interestOps() & (~ops));
    }

    private static String toString(SocketAddress address) {
        if (address == null) return null;
        InetSocketAddress addr = (InetSocketAddress) address;
        String ip = addr.getAddress().getHostAddress();
        int port = addr.getPort();
        return String.format("%s:%d", ip, port);
    }

    private static String toString(SelectableChannel s) {
        String str = s.toString();
        return str.substring(SocketChannel.class.getName().length());
    }

    private static String toString(SocketChannel channel) {
        String local = null;
        try {
            local = toString(channel.getLocalAddress());
        } catch (IOException ignored) { }
        String remote = null;
        try {
            remote = toString(channel.getRemoteAddress());
        } catch (IOException ignored) { }
        return String.format("[%s - %s %s]", local, remote,
                channel.isConnected() ? "connected" : channel.isConnectionPending() ? "connection pending" : "disconnected");
    }

    private class ChannelPair {
        private SocketChannel local;
        private SocketChannel remote;
        private ByteBuffer straightBuffer;
        private ByteBuffer reverseBuffer;

        private ChannelPair(int bufferSize) {
            this(null, null, bufferSize);
        }

        private ChannelPair(SocketChannel local, SocketChannel remote, int bufferSize) {
            this.local = local;
            this.remote = remote;
            this.straightBuffer = ByteBuffer.allocate(bufferSize);
            this.reverseBuffer = ByteBuffer.allocate(bufferSize);
        }

        private int read(boolean straight) throws IOException {
            SocketChannel in = straight ? local : remote;
            SocketChannel out = straight ? remote : local;
            ByteBuffer buffer = straight ? straightBuffer : reverseBuffer;
            int bytesRead = in.read(buffer);
            if (bytesRead > 0 && out != null) {
                addOps(out.keyFor(selector), OP_WRITE);
            }

            if (bytesRead == -1) {
                // eof
                if (out.isOpen()) {
                    out.shutdownInput();
                }
                in.keyFor(selector).cancel();
                in.close();
            }
            return bytesRead;
        }

        private int write(boolean straight) throws IOException {
            SocketChannel out = straight ? remote : local;
            ByteBuffer buffer = straight ? straightBuffer : reverseBuffer;

            buffer.flip();

            int bytesWritten = out.write(buffer);

            if (!buffer.hasRemaining()) {
                removeOps(out.keyFor(selector), OP_WRITE);
            }

            if (bytesWritten > 0) {
                buffer.compact();
            }

            return bytesWritten;
        }

        @Override
        public String toString() {
            return String.format("ChannelPair{local: %s remote: %s}",
                    PortForwarder.toString(local), PortForwarder.toString(remote));
        }
    }
}
