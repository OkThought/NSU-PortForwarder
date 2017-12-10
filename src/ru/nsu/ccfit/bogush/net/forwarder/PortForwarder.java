package ru.nsu.ccfit.bogush.net.forwarder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.ArrayList;

public class PortForwarder {
    private static void usage() {
        System.out.print(
                "Usage\n\n\t" +

                "<program_name> lport rhost rport\n\n" +

                "Description\n\n\t" +

                "A port forwarder is a program which lives between client \n" +
                "connecting to some internet service and the service itself.\n" +
                "It takes incoming bytes on from local tcp connection and\n" +
                "forwards them to the requested service connection pretending\n" +
                "the client\n\n" +

                "Arguments\n\n\t" +

                "lport - the port on which the forwarder works.\n" +
                "It is a port which the client passes to address line \n" +
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

        portForwarder.loop();
    }

    private final ServerSocket serverSocket;
    private final InetSocketAddress remoteSocketAddress;
    private final ArrayList<Connection> connections;
    private static final int BUFF_SIZE = 1024;
    private final byte[] buffer = new byte[BUFF_SIZE];

    private PortForwarder(int lport, InetAddress rhost, int rport) throws IOException {
        serverSocket = new ServerSocket(lport);
        remoteSocketAddress = new InetSocketAddress(rhost, rport);
        connections = new ArrayList<>();
    }

    private void loop() {
        acceptNewConnection();
        forwardInputOutput();
    }

    private void acceptNewConnection() {
        Connection connection = new Connection();
        try {
            connection.local = serverSocket.accept();
            connection.localInput = connection.local.getInputStream();
            connection.localOutput = connection.local.getOutputStream();
        } catch (SocketTimeoutException e) {
            return;
        } catch (IOException e) {
            e.printStackTrace();
            // TODO: 12/10/17 correctly handle exception
        }

        if (connection.local == null) return;

        try {
            connection.remote = new Socket();
            connection.remote.connect(remoteSocketAddress);
            connection.remoteInput = connection.remote.getInputStream();
            connection.remoteOutput = connection.remote.getOutputStream();
        } catch (IOException e) {
            e.printStackTrace();
            try {
                connection.local.close();
                if (connection.remote != null) {
                    connection.remote.close();
                }
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            // TODO: 12/10/17 correctly handle exception
        }

        connections.add(connection);
    }

    private void forwardInputOutput() {
        int connectionIndex = 0;
        for (Connection connection : connections) {
            try {
                int bytesForwarded;
                bytesForwarded = forward(connection.remoteInput, connection.localOutput);

                if (bytesForwarded >= 0) {
                    bytesForwarded = forward(connection.localInput, connection.remoteOutput);
                }

                if (bytesForwarded < 0) {
                    // got eof from remote socket
                    connection.remote.close();
                    connection.local.close();
                    connections.remove(connectionIndex);
                    continue;
                }
            } catch (IOException e) {
                e.printStackTrace();
                // TODO: 12/10/17 correctly handle exception
            }

            ++connectionIndex;
        }
    }

    private int forward(InputStream in, OutputStream out) throws IOException {
        int available = in.available();
        int bytesRead = in.read(buffer, 0, Math.min(available, buffer.length));
        if (bytesRead > 0) {
            out.write(buffer, 0, bytesRead);
        }
        return bytesRead;
    }

    private static class Connection {
        private Socket local;
        private Socket remote;
        private InputStream localInput;
        private InputStream remoteInput;
        private OutputStream localOutput;
        private OutputStream remoteOutput;
    }
}
