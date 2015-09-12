package org.dragonet.net.inf.mcpe.jraklib;

import java.io.ByteArrayInputStream;
import net.beaconpe.jraklib.JRakLib;
import net.beaconpe.jraklib.protocol.EncapsulatedPacket;
import net.beaconpe.jraklib.server.JRakLibServer;
import net.beaconpe.jraklib.server.ServerHandler;
import net.beaconpe.jraklib.server.ServerInstance;
import org.dragonet.net.SessionManager;
import org.dragonet.net.inf.mcpe.NetworkChannel;
import org.dragonet.net.packet.minecraft.BatchPacket;
import org.dragonet.net.packet.minecraft.PEPacket;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import org.dragonet.net.inf.mcpe.PENetworkClient;
import org.dragonet.utilities.DragonetVersioning;

/**
 * The Interface for communicating with JRakLib.
 *
 * @author jython234
 */
public class JRakLibInterface implements ServerInstance{
    private JRakLibServer rakLibServer;
    private ServerHandler handler;

    @Getter
    private SessionManager manager;
    
    private final Map<String, PENetworkClient> clientMap = new ConcurrentHashMap<>();
    private final Map<PENetworkClient, String> idMap = new ConcurrentHashMap<>();

    public JRakLibInterface(SessionManager manager, InetSocketAddress address) throws Exception {
        this.manager = manager;
        this.rakLibServer = new JRakLibServer(new JRakLibLogger(manager.getServer().getLogger()), address.getPort(), address.getHostString());
        //rakLibServer.setName("MCPE;" + manager.getServer().getServer().getServerName() + " (Dragonet " + DragonetVersioning.DRAGONET_VERSION + ");" + DragonetVersioning.MINECRAFT_PE_PROTOCOL + ";MCPC " + DragonetVersioning.MINECRAFT_PC_VERSION + ", MCPE " + DragonetVersioning.MINECRAFT_PE_VERSION + ";-1;" + manager.getServer().getServer().getMaxPlayers());
        //^^^ Set the JRakLib Thread name to the Server list MOTD lol
        this.handler = new ServerHandler(rakLibServer, this);
        if(!rakLibServer.isAlive() || rakLibServer.isInterrupted() || rakLibServer.isShutdown()){
            //DEAD
            throw new Exception("Faild to bind on port! ");
        }
        manager.getServer().getLogger().info("JRakLib Server started on: "+address.toString());
        handler.sendOption("name", "MCPE;" + manager.getServer().getServer().getServerName() + " (Dragonet " + DragonetVersioning.DRAGONET_VERSION + ");" + DragonetVersioning.MINECRAFT_PE_PROTOCOL + ";" + DragonetVersioning.MINECRAFT_PE_VERSION + ";-1;" + manager.getServer().getServer().getMaxPlayers());
    }
    
    public void onTick(){
        while(handler.handlePacket()){}
        
        if(rakLibServer.getState() == Thread.State.TERMINATED){
            manager.getServer().getLogger().error("Minecraft: PE networking handler stopped unexpectly! Did the server crash? ");
            handler.shutdown();
        }
    }
    
    public void shutdown(){
        handler.shutdown();
        rakLibServer.shutdown();
    }

    @Override
    public void openSession(String identifier, String address, int port, long clientID) {
        manager.getServer().getLogger().info("(" + identifier + ") New session with clientID: " + clientID);
        PENetworkClient client = new PENetworkClient(this, identifier);
        clientMap.put(identifier, client);
        idMap.put(client, identifier);
    }

    @Override
    public void closeSession(String identifier, String reason) {
        manager.getServer().getLogger().info("(" + identifier + ") Session closed with reason: " + reason);
        if(!clientMap.containsKey(identifier)) return;
        PENetworkClient client = clientMap.get(identifier);
        client.disconnect(reason);
        
        clientMap.remove(identifier);
        idMap.remove(client);
    }

    @Override
    public void handleEncapsulated(String identifier, EncapsulatedPacket encapsulatedPacket, int flags) {
        if(!clientMap.containsKey(identifier)) return;
        manager.getServer().getLogger().info("("+identifier+") PACKET IN: "+dumpHexFromBytes(encapsulatedPacket.buffer));
        PENetworkClient client = clientMap.get(identifier);
        client.processPacketBuffer(encapsulatedPacket.buffer);
    }

    @Override
    public void handleRaw(String address, int port, byte[] payload) {
        manager.getServer().getLogger().info("(" + address + ":" + port + ", RAW) PACKET IN: " + dumpHexFromBytes(payload));
    }

    @Override
    public void notifyACK(String identifier, int identifierACK) {
        //Not needed
    }

    @Override
    public void handleOption(String option, String value) {
        //TODO: Handle bandwith usage here
    }

    /**
     * Wraps <code>bytes</code> into an encapsulated packet and sends it.
     * @param session The Session this packet is being sent from.
     * @param packet The Packet being sent.
     * @param immediate If the packet should be sent immediately (no compression, skips packet queues)
     */
    public void sendPacket(PENetworkClient session, PEPacket packet, boolean immediate){
        if(idMap.containsKey(session)){
            if(packet.getData() == null){
                packet.encode();
            }
            if(!immediate && !(packet instanceof BatchPacket) && (packet.getData().length >= 512)){ //TODO: Compression threshold config
                BatchPacket bp = new BatchPacket();
                bp.packets.add(packet);
                bp.encode();
                sendPacket(session, bp, false);
            }
            EncapsulatedPacket pk = new EncapsulatedPacket();
            pk.buffer = packet.getData();
            pk.messageIndex = 0;
            if(packet.getChannel() != NetworkChannel.CHANNEL_NONE){
                pk.reliability = 3;
                pk.orderChannel = packet.getChannel().getAsByte();
                pk.orderIndex = 0;
            } else {
                pk.reliability = 2;
            }
            handler.sendEncapsulated(session.getRaklibClientID(), pk, (byte) ((byte) 0 | (immediate || packet.getChannel() == NetworkChannel.CHANNEL_PRIORITY ? JRakLib.PRIORITY_IMMEDIATE : JRakLib.PRIORITY_NORMAL)));
        } else {
            throw new IllegalArgumentException("Invalid session: "+session.toString());
        }
    }

    public static String dumpHexFromBytes(byte[] bytes){
        StringBuilder sb = new StringBuilder();
        for(byte b : bytes){
            sb.append(String.format("%02X", b)+" ");
        }
        return sb.toString();
    }
}
