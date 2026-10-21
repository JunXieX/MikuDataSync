package com.junxiex.mikudatasync.paper.command;

import com.junxiex.mikudatasync.common.CodecBench;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import com.junxiex.mikudatasync.paper.CaptureService;
import com.junxiex.mikudatasync.paper.IpcClient;
import com.junxiex.mikudatasync.paper.NmsBridge;
import com.junxiex.mikudatasync.paper.PaperConfig;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class MdsCommand {

    private MdsCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> node(IpcClient ipcClient, PaperConfig config,
                                                              CaptureService captureService) {
        return Commands.literal("mds")
                .requires(source -> source.getSender().hasPermission("mikudatasync.admin"))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().getSender().sendMessage(Component.text(
                            "MikuDataSync | ipc=" + ipcClient.state() + " | server=" + config.serverId()));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("bench").executes(context -> {
                    CodecBench.Result result = CodecBench.run(200, 2_000);
                    context.getSource().getSender().sendMessage(Component.text(CodecBench.describe(result)));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("selftest").executes(context -> {
                    CommandSender sender = context.getSource().getSender();
                    StringBuilder text = new StringBuilder("bridges: cursor=")
                            .append(NmsBridge.cursorBridgeAvailable())
                            .append(", data=").append(NmsBridge.dataBridgeAvailable())
                            .append(", dataVersion=").append(NmsBridge.dataVersion());
                    if (sender instanceof Player player) {
                        SnapshotMessage message = captureService.capture(player, "selftest");
                        text.append(", capture=").append(message == null ? "failed" : message.envelope().length + "B");
                    }
                    sender.sendMessage(Component.text(text.toString()));
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }
}
