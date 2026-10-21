package com.junxiex.mikudatasync.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataCodecTest {

    @Test
    void statisticsRoundTrip() throws Exception {
        List<StatisticsList.Entry> entries = List.of(
                new StatisticsList.Entry("minecraft:custom", "minecraft:deaths", 3),
                new StatisticsList.Entry("minecraft:mined", "minecraft:stone", 42)
        );
        List<StatisticsList.Entry> decoded = StatisticsList.decode(StatisticsList.encode(entries));
        assertEquals(entries, decoded);
    }

    @Test
    void statisticsNullValueKey() throws Exception {
        byte[] bytes = StatisticsList.encode(List.of(new StatisticsList.Entry("minecraft:custom", null, 7)));
        List<StatisticsList.Entry> decoded = StatisticsList.decode(bytes);
        assertEquals(1, decoded.size());
        assertNull(decoded.get(0).valueKey());
    }

    @Test
    void advancementsRoundTrip() throws Exception {
        List<AdvancementList.Entry> entries = List.of(
                new AdvancementList.Entry("minecraft:story/root", List.of("crafting_table")),
                new AdvancementList.Entry("minecraft:story/mine_stone", List.of("get_stone"))
        );
        List<AdvancementList.Entry> decoded = AdvancementList.decode(AdvancementList.encode(entries));
        assertEquals(entries, decoded);
        assertTrue(decoded.get(0).criteria().contains("crafting_table"));
    }

    @Test
    void coreDataRoundTrip() throws Exception {
        CoreData data = new CoreData(19.5, 2.0, 18, 4.5f, 0.25f, 12, 0.75f, 2500,
                "SURVIVAL", true, false, 0.05f, 0.1f, 3, 0, 0, 300, 0.0f);
        CoreData decoded = CoreData.decode(data.encode());
        assertEquals(data, decoded);
    }
}
