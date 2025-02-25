package com.group15.kvserver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.group15.kvserver.utils.Logger;

/**
 * Enum representing different types of requests handled by the server.
 */
enum RequestType {
    AuthRequest((short)0),
    RegisterRequest((short)1),
    PutRequest((short)2),
    GetRequest((short)3),
    MultiPutRequest((short)4),
    MultiGetRequest((short)5),
    GetWhenRequest((short)6),
    DisconnectRequest((short)7);

    private final short value;

    RequestType(short value) {
        this.value = value;
    }

    public short getValue() {
        return value;
    }
}

/**
 * A class representing the server's database, including methods for handling sharding and locks.
 */
class ServerDatabase {
    int databaseShardsCount;
    int usersShardsCount;

    /* Stores data for different database shards */
    List<Map<String, byte[]>> databaseShards;
    /* Stores user data for different user shards */
    List<Map<String, String>> usersShards;

    /* Locks for database shards */
    List<ReentrantReadWriteLock> databaseLocks;
    /* Locks for user shards */
    List<ReentrantLock> usersLocks;
    /* Conditions to notify */
    Map<String, Condition> conditions;

    /**
     * Constructor initializes the server database with the specified number of shards.
     */
    public ServerDatabase(int databaseShardsCount, int usersShardsCount) {
        this.databaseShardsCount = databaseShardsCount;
        this.usersShardsCount = usersShardsCount;
        
        this.databaseShards = new java.util.ArrayList<>();
        this.usersShards = new java.util.ArrayList<>();

        this.databaseLocks = new java.util.ArrayList<>();
        this.usersLocks = new java.util.ArrayList<>();
        
        this.conditions = new HashMap<>(); 

        for (int i = 0; i < databaseShardsCount; i++) {
            this.databaseShards.add(new HashMap<>());
            this.databaseLocks.add(new ReentrantReadWriteLock());
        }

        for (int i = 0; i < usersShardsCount; i++) {
            this.usersShards.add(new HashMap<>());
            this.usersLocks.add(new ReentrantLock());
        }
    }

    /**
     * Calculates the shard index for a given key based on the hash of the key.
     */
    public int getDatabaseShardIndex(String key) {
        return Math.abs(key.hashCode()) % databaseShardsCount;
    }

    /**
     * Calculates the shard index for a given user based on the hash of the username.
     */
    public int getUsersShardIndex(String key) {
        return Math.abs(key.hashCode()) % usersShardsCount;
    }
}

/**
 * A class that represents a worker that handles communication with a client.
 * This class handles the logic for processing different requests sent by the client.
 */
class ServerWorker implements Runnable {
    private Socket socket;
    private ServerDatabase database;
    private final Demultiplexer demultiplexer;
    private Map<Condition, List<Integer>> conditionsTags = new HashMap<>();

    /**
     * Constructor initializes the worker with the client's socket and server database.
     */
    public ServerWorker(Socket socket, ServerDatabase database) throws IOException {
        this.demultiplexer = new Demultiplexer(new TaggedConnection(socket));
        this.database = database;
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            boolean running = true;
            while (running) {
                if (socket.isClosed()) {
                    running = false;
                    break;
                }

                TaggedConnection.Frame frame = new TaggedConnection.Frame(0, (short)0, new byte[0]);
                try {
                    // Receive a request frame from the client
                    frame = demultiplexer.receiveAny();
                } catch (InterruptedException e) {
                    Logger.log(e.getMessage(), Logger.LogLevel.ERROR);
                }

                // Process request
                ByteArrayInputStream bais = new ByteArrayInputStream(frame.data);
                DataInputStream in = new DataInputStream(bais);
                try {
                    short requestType = in.readShort();
                    if (requestType == RequestType.DisconnectRequest.getValue()) {
                        System.out.println("Client requested disconnect.");
                        running = false;
                        demultiplexer.send(frame.tag, requestType, new byte[0]);
                        break;
                    }
                    if (requestType >= 0 && requestType < RequestType.values().length) {
                        RequestType r = RequestType.values()[requestType];
                        byte[] stream = handleRequest(r, in, frame.tag);
                        if (stream != null) {
                            demultiplexer.send(frame.tag, r.getValue(), stream);
                        }
                    } else {
                        Logger.log("Invalid request type: " + requestType, Logger.LogLevel.ERROR);
                    }
                }
                catch (EOFException e) {
                    // Client disconnects
                    running = false;
                }
            }
        }
        catch (IOException e) {
            Logger.log(e.getMessage(), Logger.LogLevel.ERROR);
        } finally {
            try {
                demultiplexer.close();
                socket.close();
                Logger.log("Socket closed.", Logger.LogLevel.INFO);
            } catch (IOException e) {
                Logger.log(e.getMessage(), Logger.LogLevel.ERROR);
            } finally {
                Server.signalClientDisconnection();
            }
        }
    }

    /**
     * Handles different types of requests from the client and returns the appropriate response.
     */
    public byte[] handleRequest(RequestType requestType, DataInputStream in, int tag){
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            switch (requestType) {
                case AuthRequest:
                    handleAuthRequest(in, out);
                    break;
                case RegisterRequest:
                    handleRegisterRequest(in, out);
                    break;
                case PutRequest:
                    handlePutRequest(in, out);
                    break;
                case GetRequest:
                    handleGetRequest(in, out);
                    break;
                case MultiPutRequest:
                    handleMultiPutRequest(in, out);
                    break;
                case MultiGetRequest:
                    handleMultiGetRequest(in, out);
                    break;
                case GetWhenRequest:
                    int flag = handleGetWhenRequest(in, out, tag);
                    if (flag == -1) {
                        return null;
                    }
                    break;
                default:
                    break;
            }
            return baos.toByteArray();
        } catch (IOException e) {
            Logger.log(e.getMessage(), Logger.LogLevel.ERROR);
            return null;
        }
    }

    /*
     * Handles an authentication request from the client.
     * 
     * @param in The input stream to read the request from.
     * @param out The output stream to write the response to.
     */
    private void handleAuthRequest(DataInputStream in, DataOutputStream out) throws IOException {
        String username = in.readUTF();
        String password = in.readUTF();
        int userShardIndex = database.getUsersShardIndex(username);
        database.usersLocks.get(userShardIndex).lock();
        try {
            Map<String, String> currentShard = database.usersShards.get(userShardIndex);
            if (currentShard.containsKey(username) && currentShard.get(username).equals(password)) {
                out.writeBoolean(true);
            }
        } 
        finally {
            database.usersLocks.get(userShardIndex).unlock();
        }
    }

    /*
     * Handles a registration request from the client.
     * 
     * @param in The input stream to read the request from.
     * @param out The output stream to write the response to.
     */
    private void handleRegisterRequest(DataInputStream in, DataOutputStream out) throws IOException {
        String username = in.readUTF();
        String password = in.readUTF();
        int userShardIndex = database.getUsersShardIndex(username);
        database.usersLocks.get(userShardIndex).lock();
        try {
            Map<String, String> currentShard = database.usersShards.get(userShardIndex);
            if (currentShard.containsKey(username)) {
                out.writeBoolean(false);
            } else {
                currentShard.put(username, password);
                out.writeBoolean(true);
            }
        } finally {
            database.usersLocks.get(userShardIndex).unlock();
        }
    }

    /*
     * Handles a put request from the client.
     * 
     * @param in The input stream to read the request from.
     * @param out The output stream to write the response to.
     */
    private void handlePutRequest(DataInputStream in, DataOutputStream out) throws IOException{
        String key = in.readUTF();
        int valueLength = in.readInt();
        byte[] value = new byte[valueLength];
        in.readFully(value);

        put(key, value);
    }

    /*
     * Handles a get request from the client.
     * 
     * @param in The input stream to read the request from.
     * @param out The output stream to write the response to.
     */
    private void handleGetRequest(DataInputStream in, DataOutputStream out) throws IOException{
        // KEY
        String key = in.readUTF();
        byte[] value = get(key);

        // VALUE SIZE | VALUE
        out.writeInt(value.length);
        out.write(value);
    }

    /*
     * Handles a multi-put request from the client.
     * 
     * @param in The input stream to read the request from.
     * @param out The output stream to write the response to.
     */
    private void handleMultiPutRequest(DataInputStream in, DataOutputStream out) throws IOException {
        // N PAIRS | KEY | VALUE LENGTH | VALUE | KEY | VALUE LENGTH | VALUE | ...
        int numberOfPairs = in.readInt();
        Map<String, byte[]> pairs = new java.util.HashMap<>();

        for (int i = 0; i < numberOfPairs; i++) {
            String key = in.readUTF();
            int valueLength = in.readInt();
            byte[] value = new byte[valueLength];
            in.readFully(value);
            pairs.put(key, value);
        }

        multiPut(pairs);
    }

    /*
     * Handles a multi-get request from the client.
     * 
     * @param in The input stream to read the request from.
     * @param out The output stream to write the response to.
     */
    private void handleMultiGetRequest(DataInputStream in, DataOutputStream out) throws IOException {
        // N KEYS | KEY | ...
        int numberOfKeys = in.readInt();
        Set<String> keys = new java.util.HashSet<>();
        for (int i = 0; i < numberOfKeys; i++) {
            String key = in.readUTF();
            keys.add(key);
        }
        Map<String, byte[]> pairs = multiGet(keys);

        // N PAIRS | KEY | VALUE LENGTH | VALUE ..
        out.writeInt(numberOfKeys);
        for (Map.Entry<String, byte[]> pair : pairs.entrySet()) {
            out.writeUTF(pair.getKey());
            byte[] value = pair.getValue();
            out.writeInt(value.length);
            out.write(value);
        }
    }

    /*
     * Handles a get-when request from the client.
     * 
     * @param in The input stream to read the request from.
     * @param out The output stream to write the response to.
     * @param tag The tag associated with the request.
     * @return 0 if the request was successful, -1 otherwise.
     */
    private int handleGetWhenRequest(DataInputStream in, DataOutputStream out, int tag) throws IOException {
        // Chaves e valores para a condição
        String key = in.readUTF();
        String keyCond = in.readUTF();
        int valueCondLength = in.readInt();
        byte[] valueCond = new byte[valueCondLength];
        in.readFully(valueCond);

        int shardIndexCond = database.getDatabaseShardIndex(keyCond);
        ReentrantReadWriteLock lock = database.databaseLocks.get(shardIndexCond);
        Condition condition;
        lock.writeLock().lock();
        try {
            condition = database.conditions.computeIfAbsent(keyCond, k -> lock.writeLock().newCondition());
            conditionsTags.putIfAbsent(condition, new java.util.ArrayList<>());
            conditionsTags.get(condition).add(tag);
        }
        finally {
            lock.writeLock().unlock();
        }

        byte[] result = getWhen(key, keyCond, valueCond);
        if (result != null) {
            out.writeInt(result.length);
            out.write(result);
            return 0;
        }
        else {
            return -1;
        }
    }

    /**
     * Puts a key-value pair into the database.
     * 
     * @param key The key to store.
     * @param value The value to store.
     */
    private void put(String key, byte[] value) {

        int shardIndex = database.getDatabaseShardIndex(key);
        database.databaseLocks.get(shardIndex).writeLock().lock();
        try {
            Map<String, byte[]> currentShard = database.databaseShards.get(shardIndex);
            currentShard.put(key, value);
            updateConditionAndNotify(key);
        } finally {
            database.databaseLocks.get(shardIndex).writeLock().unlock();
        }
    }

    /**
     * Gets the value associated with a key from the database.
     * 
     * @param key The key to retrieve.
     * @return The value associated with the key.
     */
    private byte[] get(String key) {
        int shardIndex = database.getDatabaseShardIndex(key);
        database.databaseLocks.get(shardIndex).readLock().lock();
        try {
            Map<String, byte[]> currentShard = database.databaseShards.get(shardIndex);
            return currentShard.get(key);
        } finally {
            database.databaseLocks.get(shardIndex).readLock().unlock();
        }
    }
    
    /**
     * Puts multiple key-value pairs into the database.
     * 
     * @param pairs A map of key-value pairs to store.
     */
    private void multiPut(Map<String, byte[]> pairs) {
        Map<Integer, Map<String, byte[]>> pairsByShard = new java.util.HashMap<>();
        for (Map.Entry<String, byte[]> entry : pairs.entrySet()) {
            String key = entry.getKey();
            int shardIndex = database.getDatabaseShardIndex(key);
            if (!pairsByShard.containsKey(shardIndex)) {
                pairsByShard.put(shardIndex, new java.util.HashMap<>());
            }
            pairsByShard.get(shardIndex).put(key, entry.getValue());
        }

        for(Map.Entry<Integer, Map<String, byte[]>> shardPairs : pairsByShard.entrySet()) {
            int shardIndex = shardPairs.getKey();
            database.databaseLocks.get(shardIndex).writeLock().lock();
        }

        for(Map.Entry<Integer, Map<String, byte[]>> shardPairs : pairsByShard.entrySet()) {
            int shardIndex = shardPairs.getKey();
            Map<String, byte[]> currentShard = database.databaseShards.get(shardIndex);
            Map<String, byte[]> par = shardPairs.getValue();
            for (Map.Entry<String, byte[]> entry : par.entrySet()) {
                String key = entry.getKey();
                byte[] value = entry.getValue();
                currentShard.put(key, value);
                updateConditionAndNotify(key);
            }
            database.databaseLocks.get(shardIndex).writeLock().unlock();
        }
    }

    /**
     * Gets multiple values associated with a set of keys from the database.
     * 
     * @param keys A set of keys to retrieve.
     * @return A map of key-value pairs.
     */
    private Map<String, byte[]> multiGet(Set<String> keys) {
        Map<String, byte[]> pairs = new java.util.HashMap<>();
        Map<Integer, List<String>> keysByShard = new java.util.HashMap<>();
        for (String key : keys) {
            int shardIndex = database.getDatabaseShardIndex(key);
            if (!keysByShard.containsKey(shardIndex)) {
                keysByShard.put(shardIndex, new java.util.ArrayList<>());
            }
            keysByShard.get(shardIndex).add(key);
        }

        for (Map.Entry<Integer, List<String>> entry : keysByShard.entrySet()) {
            int shardIndex = entry.getKey();
            database.databaseLocks.get(shardIndex).readLock().lock();
        }

        for(Map.Entry<Integer, List<String>> shardKeys : keysByShard.entrySet()) {
            int shardIndex = shardKeys.getKey();
            List<String> keysByShardList = shardKeys.getValue();
            Map<String, byte[]> currentShard = database.databaseShards.get(shardIndex);
            for (String key : keysByShardList) {
                pairs.put(key, currentShard.get(key));
            }
            database.databaseLocks.get(shardIndex).readLock().unlock();
        }
        
        return pairs;
    }

    /**
     * Gets the value associated with a key from the database when a condition is met.
     * 
     * @param key The key to retrieve.
     * @param keyCond The key representing the condition.
     * @param valueCond The value representing the condition.
     * @return The value associated with the key.
     * @throws IOException If an error occurs during the operation.
     */
    private byte[] getWhen(String key, String keyCond, byte[] valueCond) throws IOException {
        int shardIndexCond = database.getDatabaseShardIndex(keyCond);
        ReentrantReadWriteLock lock = database.databaseLocks.get(shardIndexCond);
        Condition condition;
        lock.writeLock().lock();
        try {
            Map<String, byte[]> currentShardCond = database.databaseShards.get(shardIndexCond);
            condition = database.conditions.computeIfAbsent(keyCond, k -> lock.writeLock().newCondition());

            // Check the condition before waiting
            if (java.util.Arrays.equals(currentShardCond.get(keyCond), valueCond)) {
                Logger.log("Condition met for key: " + keyCond, Logger.LogLevel.INFO);
                conditionsTags.get(condition).remove(0);
                return fetchTargetValue(key);
            }
        } finally {
            lock.writeLock().unlock();
        }

        final Condition finalCondition = condition;
        Runnable task = () -> {
            lock.writeLock().lock();
            try {
                Map<String, byte[]> currentShardCond = database.databaseShards.get(shardIndexCond);
                while (!java.util.Arrays.equals(currentShardCond.get(keyCond), valueCond)) {
                    try {
                        finalCondition.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        Logger.log("Interrupted while waiting for condition on key: " + keyCond, Logger.LogLevel.ERROR);
                        return;
                    }
                }
                byte[] result = fetchTargetValue(key);
                if (result != null) {
                    Logger.log("Condition met for key: " + keyCond, Logger.LogLevel.INFO);
                    try {
                        int tag = conditionsTags.get(finalCondition).get(0);
                        conditionsTags.get(finalCondition).remove(0);
                        demultiplexer.send(tag, RequestType.GetWhenRequest.getValue(), result);
                        Logger.log("Sent result for key: " + keyCond, Logger.LogLevel.INFO);
                    } catch (IOException e) {
                        Logger.log("Failed to send result: " + e.getMessage(), Logger.LogLevel.ERROR);
                    }
                }
            } catch (IOException e) {
                            e.printStackTrace();
                        } finally {
                lock.writeLock().unlock();
            }
        };
        new Thread(task).start();
        return null;
    }

    /**
     * Fetches the value associated with a key from the database.
     * 
     * @param key The key to retrieve.
     * @return The value associated with the key.
     * @throws IOException If an error occurs during the operation.
     */
    private byte[] fetchTargetValue(String key) throws IOException {
        int shardIndex = database.getDatabaseShardIndex(key);
        ReentrantReadWriteLock targetLock = database.databaseLocks.get(shardIndex);
        targetLock.readLock().lock();
        try {
            Map<String, byte[]> currentShard = database.databaseShards.get(shardIndex);
            return currentShard.get(key);
        } finally {
            targetLock.readLock().unlock();
        }
    }

    /**
     * Updates the condition and notifies waiting threads.
     * 
     * @param keyCond The key representing the condition.
     */
    private void updateConditionAndNotify(String keyCond) {
        int shardIndexCond = database.getDatabaseShardIndex(keyCond);
        ReentrantReadWriteLock lock = database.databaseLocks.get(shardIndexCond);
        lock.writeLock().lock();
        try {
            Map<String, byte[]> currentShardCond = database.databaseShards.get(shardIndexCond);

            if (currentShardCond.containsKey(keyCond)) {
                Condition condition = database.conditions.get(keyCond);
                Logger.log("Notifying condition for key: " + keyCond, Logger.LogLevel.INFO);
                if (condition != null) {
                    condition.signalAll();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}

/**
 * The main server class that listens for incoming client connections and processes requests.
 */
public class Server {
    static int connectedClients = 0;
    /* Lock for managing the number of active clients */
    static ReentrantLock lock = new ReentrantLock();
    /* Lock for managing client connections */
    static ReentrantLock lockC = new ReentrantLock();
    /* Condition for allowing client connections */
    static Condition allowClientConnection = lockC.newCondition();

    /**
     * Main method that starts the server and accepts client connections.
     */
    public static void main(String[] args) throws IOException {
        List<Integer> arguments = new java.util.ArrayList<>();

        if(args.length == 3) {
            for(String arg : args) {
                try {
                    arguments.add(Integer.parseInt(arg));
                } catch (NumberFormatException e) {
                    System.out.println("Usage: java Server <max-clients> <database-shards> <user-shards>");
                    return;
                }
            }
        } else {
            System.out.println("Usage: java Server <max-clients> <database-shards> <user-shards>");
            return;
        }

        int maxClients = arguments.get(0);
        ServerDatabase database = new ServerDatabase(arguments.get(1), arguments.get(2));
        ServerSocket serverSocket = new ServerSocket(12345);

        Logger.log("Server started. Listening on port 12345", Logger.LogLevel.INFO);
        // log maxClients, databaseShards, userShards
        Logger.log("Max clients: " + maxClients + ", Database shards: " + arguments.get(1), Logger.LogLevel.INFO);

        boolean running = true;
        while (running) {
            lock.lock();
            try {
                while (connectedClients >= maxClients) {
                    lockC.lock();
                    try{
                        allowClientConnection.await();
                    }
                    finally{
                        lockC.unlock();
                    }
                }

                Socket socket = serverSocket.accept();
                connectedClients++;
                Logger.log("Client connected. Active clients: " + connectedClients, Logger.LogLevel.INFO);
                Thread worker = new Thread(new ServerWorker(socket, database));
                worker.start();

            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                System.out.println("Unlocking");
                lock.unlock();}
        }
        serverSocket.close();
    }

    /**
     * Decreases the active client count and signals waiting threads to allow new connections.
     */
    public static void signalClientDisconnection(){
        lockC.lock();
        try{
            connectedClients--;
            Logger.log("Client disconnected. Active clients: " + connectedClients, Logger.LogLevel.INFO);
            allowClientConnection.signalAll();
        }
        finally {
            lockC.unlock();
        }
    }
}
