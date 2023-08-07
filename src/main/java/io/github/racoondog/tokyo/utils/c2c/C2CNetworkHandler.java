package io.github.racoondog.tokyo.utils.c2c;

import io.github.racoondog.tokyo.Tokyo;
import io.github.racoondog.tokyo.mixin.IKeyboard;
import io.github.racoondog.tokyo.mixin.IMouse;
import io.github.racoondog.tokyo.systems.modules.ChatManager;
import io.github.racoondog.tokyo.systems.modules.Prefix;
import io.github.racoondog.tokyo.systems.config.TokyoConfig;
import io.github.racoondog.tokyo.systems.modules.clientsync.ClientSync;
import io.github.racoondog.tokyo.utils.ChunkManagerHelper;
import io.github.racoondog.tokyo.utils.RunnableClickEvent;
import io.github.racoondog.tokyo.utils.c2c.packets.*;
import io.netty.buffer.Unpooled;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.ProfileKeysImpl;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.encryption.PlayerKeyPair;
import net.minecraft.network.encryption.PlayerPublicKey;
import net.minecraft.network.encryption.PublicPlayerSession;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.commons.codec.digest.DigestUtils;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Optional;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Environment(EnvType.CLIENT)
public class C2CNetworkHandler implements C2CPacketListener {
    public static final C2CNetworkHandler INSTANCE = new C2CNetworkHandler();

    private C2CNetworkHandler() {
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    public void sendPacket(C2CPacket packet) {
        byte[][] chunks = encodePacket(packet);

        chunks = encryptKey(chunks);

        String message = TokyoConfig.INSTANCE.packetIdentifier.get() + joinPacket(chunks);

        if (message.length() >= 256) {
            Tokyo.LOG.warn("Packet too long!");
        }

        if (ChatManager.INSTANCE.isActive()) ChatManager.INSTANCE.queueSend(message, ChatManager.Priority.Chat);
        else MinecraftClient.getInstance().getNetworkHandler().sendChatMessage(message);
    }

    public void sendPacket(C2CPacket packet, PlayerListEntry recipient) {
        PublicPlayerSession session = recipient.getSession();
        if (session == null) {
            Tokyo.LOG.warn("Public key not found for user {}.", recipient.getProfile().getName());
            return;
        }
        PlayerPublicKey ppk = session.publicKeyData();
        if (ppk == null) {
            Tokyo.LOG.warn("Public key not found for user {}.", recipient.getProfile().getName());
            return;
        }

        PublicKey key = ppk.data().key();

        byte[][] chunkedData = encodePacket(packet);

        for (int i = 0; i < chunkedData.length; i++) {
            byte[] chunk = ConversionHelper.RsaEcb.encrypt(chunkedData[i], key);
            if (chunk == null || chunk.length == 0) {
                Tokyo.LOG.warn("Packet encryption failed!");
                return;
            }
            chunkedData[i] = chunk;
        }

        String packetString = joinPacket(chunkedData);
        String commandString = "w " + recipient.getProfile().getName() + " " + TokyoConfig.INSTANCE.packetIdentifier.get() + packetString;
        if (commandString.length() >= 256) {
            Tokyo.LOG.warn("Packet too long!");
        }

        if (ChatManager.INSTANCE.isActive()) ChatManager.INSTANCE.queueSend(commandString, ChatManager.Priority.Command);
        else MinecraftClient.getInstance().getNetworkHandler().sendChatCommand(commandString);
        //todo clear sent packet from chat
        //todo possibly outgoing filter api for receivemessageevent && receivepacketevent && sendpacketevent && sentpacketevent && sendmessageevent
    }

    private static byte[][] encodePacket(C2CPacket packet) {
        PacketByteBuf buf = C2CPacketHandler.writePacket(packet);

        byte[] compressed = ConversionHelper.Gzip.compress(buf.getWrittenBytes());
        int chunks = (compressed.length + 244) / 245;
        byte[][] chunkedData = new byte[chunks][];
        for (int i = 0; i < chunks; i++) {
            //split
            int start = i * 245;
            int end = Math.min(start + 245, compressed.length);
            byte[] chunk = new byte[end - start];
            System.arraycopy(compressed, start, chunk, 0, end - start);
            chunkedData[i] = chunk;
        }

        return chunkedData;
    }

    private static byte[][] encryptKey(byte[][] bytes) {
        return bytes; //todo
    }

    private static String joinPacket(byte[][] chunks) {
        byte[] joined = new byte[chunks.length * 256];
        for (int i = 0; i < chunks.length; i++) {
            System.arraycopy(chunks[i], 0, joined, i * 256, 256);
        }

        return ConversionHelper.BaseUTF8.toUnicode(joined);
    }

    @EventHandler
    private void onChatMessage(ReceiveMessageEvent event) {
        if (!TokyoConfig.INSTANCE.c2cChat.get() && !TokyoConfig.INSTANCE.c2cWhisper.get()) return;

        String string = event.getMessage().getString();
        int index = string.indexOf(TokyoConfig.INSTANCE.packetIdentifier.get());
        if (index == -1) return;

        String packetString = string.substring(index + TokyoConfig.INSTANCE.packetIdentifier.get().length());

        handleC2CPacket(ConversionHelper.BaseUTF8.fromUnicode(packetString));
    }

    private static void handleC2CPacket(byte[] bytes) {
        byte infoByte = bytes[0];
        bytes = Arrays.copyOfRange(bytes, 1, bytes.length);

        boolean encrypted = (infoByte & 1) == 1;

        if (encrypted) {
            boolean aesEncrypted = ((infoByte >> 1) & 1) == 1;
            try {
                bytes = aesEncrypted ? decryptKey(bytes) : decryptNative(bytes);
            } catch (Exception e) {
                //Try opposite
                try {
                    bytes = aesEncrypted ? decryptNative(bytes) : decryptKey(bytes);
                } catch (Exception e2) {
                    e2.addSuppressed(e);
                    e2.printStackTrace();
                    return;
                }
            }
        }

        decompressAndHandle(bytes);
    }

    private static byte[] decryptNative(byte[] bytes) {
        if (!(mc.getProfileKeys() instanceof ProfileKeysImpl privateKeyHolder)) throw new RuntimeException("Private key not found");

        Optional<PrivateKey> key = privateKeyHolder.fetchKeyPair().join().map(PlayerKeyPair::privateKey);
        if (key.isEmpty()) throw new RuntimeException("Private key not present");

        //Separate and decrypt
        int length = bytes.length & ~0xFF;
        byte[][] chunks = new byte[length / 256][];
        int decryptedLength = 0;
        for (int i = 0; i < length; i++) {
            int index = i * 256;
            byte[] chunk = Arrays.copyOfRange(bytes, index, index + 256);
            chunk = ConversionHelper.RsaEcb.decrypt(chunk, key.get());
            if (chunk == null) throw new RuntimeException("Error decrypting data");
            decryptedLength += chunk.length;
            chunks[i] = chunk;
        }

        //Join
        byte[] decrypted = new byte[decryptedLength];
        int pos = 0;
        for (var decryptedArray : chunks) {
            System.arraycopy(decryptedArray, 0, decrypted, pos, decryptedArray.length);
            pos += decryptedArray.length;
        }

        return decrypted;
    }

    private static byte[] decryptKey(byte[] bytes) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(DigestUtils.sha1(TokyoConfig.INSTANCE.encryptionKey.get()), "AES"));

            return bytes;
        } catch (NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException e) {
            e.printStackTrace();
            return bytes;
        }
    }

    private static void decompressAndHandle(byte[] bytes) {
        handle(ConversionHelper.Gzip.uncompress(bytes));
    }

    public static void handle(byte[] bytes) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(bytes));
        int id = buf.readInt();

        // Handle
        C2CPacket packet = C2CPacketHandler.createPacket(id, buf);
        if (packet == null) return;
        try {
            packet.apply(INSTANCE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Packet handling implementation */

    @Override
    public void onWaypointC2CPacket(WaypointC2CPacket packet) {
        MutableText message = Text.empty();
        message.append(Prefix.getTokyo());
        message.append(Text.literal("Waypoint %s (%s) received. ".formatted(packet.waypoint.name.get(), packet.waypoint.pos.get())).formatted(Formatting.GRAY));
        message.append(Text.literal("[Click here to accept]").setStyle(Style.EMPTY.withColor(Formatting.GREEN).withClickEvent(new RunnableClickEvent(() -> {
            Waypoints.get().add(packet.waypoint);
            ChatUtils.info("Accepted waypoint!");
        }))));
        MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(message);
    }

    @Override
    public void onChunkC2CPacket(ChunkC2CPacket packet) {
        //ChunkManagerHelper.loadChunk(packet.chunkX, packet.chunkZ, packet.chunkData.getSectionsDataBuf(), packet.chunkData.getHeightmap(), packet.chunkData.getBlockEntities(packet.chunkX, packet.chunkZ));
        mc.world.enqueueChunkUpdate(() -> {
            WorldChunk worldChunk = mc.world.getChunkManager().getWorldChunk(packet.chunkX, packet.chunkZ, false);
            if (worldChunk != null) {
                ChunkManagerHelper.scheduleRenderChunk(worldChunk, packet.chunkX, packet.chunkZ);
            }
        });
    }

    @Override
    public void onInputSyncMouseButtonC2CPacket(InputSyncMouseButtonC2CPacket packet) {
        if (ClientSync.INSTANCE.isWorker() && ClientSync.INSTANCE.syncInputs.get()) {
            IMouse mouse = (IMouse) MinecraftClient.getInstance().mouse;
            mouse.tokyo$invokeOnMouseButton(MinecraftClient.getInstance().getWindow().getHandle(), packet.button, packet.action, packet.modifiers);
        }
    }

    @Override
    public void onInputSyncMouseScrollC2CPacket(InputSyncMouseScrollC2CPacket packet) {
        if (ClientSync.INSTANCE.isWorker() && ClientSync.INSTANCE.syncInputs.get()) {
            IMouse mouse = (IMouse) MinecraftClient.getInstance().mouse;
            mouse.tokyo$invokeOnMouseScroll(MinecraftClient.getInstance().getWindow().getHandle(), packet.horizontalScroll, packet.verticalScroll);
        }
    }

    @Override
    public void onInputSyncMouseMoveC2CPacket(InputSyncMouseMoveC2CPacket packet) {
        if (ClientSync.INSTANCE.isWorker() && ClientSync.INSTANCE.syncInputs.get()) {
            IMouse mouse = (IMouse) MinecraftClient.getInstance().mouse;
            mouse.tokyo$invokeOnCursorPos(MinecraftClient.getInstance().getWindow().getHandle(), packet.x, packet.y);
        }
    }

    @Override
    public void onInputSyncKeyPressC2CPacket(InputSyncKeyPressC2CPacket packet) {
        if (ClientSync.INSTANCE.isWorker() && ClientSync.INSTANCE.syncInputs.get()) {
            MinecraftClient.getInstance().keyboard.onKey(MinecraftClient.getInstance().getWindow().getHandle(), packet.key, packet.scancode, packet.action, packet.modifiers);
        }
    }

    @Override
    public void onInputSyncCharTypedC2CPacket(InputSyncCharTypedC2CPacket packet) {
        if (ClientSync.INSTANCE.isWorker() && ClientSync.INSTANCE.syncInputs.get()) {
            IKeyboard keyboard = (IKeyboard) MinecraftClient.getInstance().keyboard;
            keyboard.tokyo$invokeOnChar(MinecraftClient.getInstance().getWindow().getHandle(), packet.codePoint, packet.modifiers);
        }
    }
}
