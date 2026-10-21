package com.junxiex.mikudatasync.paper.command;

import com.junxiex.mikudatasync.paper.IpcClient;
import com.junxiex.mikudatasync.paper.PaperConfig;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.kyori.adventure.text.Component;

public final class MdsCommand {

    private MdsCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> node(IpcClient ipcClient, PaperConfig config) {
        return Commands.literal("mds")
                .requires(source -> source.getSender().hasPermission("mikudatasync.admin"))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().getSender().sendMessage(Component.text(
                            "MikuDataSync | ipc=" + ipcClient.state() + " | server=" + config.serverId()));
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }
}
