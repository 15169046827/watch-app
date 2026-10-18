package com.canace.x3schedule.shared;

import android.content.Context;
import android.database.DatabaseUtils;
import com.canace.x3schedule.domain.Batch;
import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Wire;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Device-only synthetic databases, never schedule.db. */
final class BatchStoreChecks {
    private final Context context;
    private int checks;

    BatchStoreChecks(Context context) {
        this.context = context;
    }

    private void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Synthetic batch check " + (checks + 1));
        }
        checks++;
    }

    int run() {
        String prefix = "schedule-test-batch-" + UUID.randomUUID();
        String phoneName = prefix + "-phone.db";
        String watchName = prefix + "-watch.db";
        try {
            byte[] first = seed(phoneName, watchName);
            restartAndDrain(phoneName, watchName, first);
            try (Store phone = new Store(context, phoneName, true);
                Store watch = new Store(context, watchName, false)) {
                conflict(phone, watch);
            }
            return checks;
        } finally {
            context.deleteDatabase(phoneName);
            context.deleteDatabase(watchName);
        }
    }

    private byte[] seed(String phoneName, String watchName) {
        try (Store phone = new Store(context, phoneName, true);
            Store watch = new Store(context, watchName, false)) {
            for (int n = 0; n < 2; n++) {
                Plan plan = new Plan();
                plan.title = "合成长历史 " + n;
                plan.anchor = LocalDate.now().minusDays(32).toString();
                plan.rule = Rule.DAYS;
                for (int index = 0; index < 30; index++) {
                    plan.steps.add(new String(new char[80]).replace('\0', '测'));
                }
                phone.save(plan);
            }
            byte[] first = phone.outgoingBatch();
            check(Batch.decode(first).manifest.bytes > Wire.MAX_BYTES);
            check(!watch.receiveBatch(first).committed);
            check(watch.plans().isEmpty());
            return first;
        }
    }

    private void restartAndDrain(String phoneName, String watchName, byte[] first) {
        byte[] last = null;
        try (Store phone = new Store(context, phoneName, true);
            Store watch = new Store(context, watchName, false)) {
            check(Arrays.equals(first, phone.outgoingBatch()));
            long pending = phone.pending();
            check(!phone.acknowledgeBatch(watch.receiveBatch(first)));
            check(phone.pending() == pending);
            Plan changed = phone.plans().get(0);
            changed.title = "排队后新改动";
            phone.save(changed);
            while (last == null) {
                byte[] packet = phone.outgoingBatch();
                Batch.Ack ack = watch.receiveBatch(packet);
                if (ack.committed) {
                    last = packet;
                } else {
                    check(watch.plans().isEmpty());
                    check(!phone.acknowledgeBatch(ack));
                }
            }
            check(watch.plans().size() == 2);
            check(watch.plans().stream().noneMatch(plan -> plan.title.equals("排队后新改动")));
        }
        try (Store phone = new Store(context, phoneName, true);
            Store watch = new Store(context, watchName, false)) {
            check(Arrays.equals(last, phone.outgoingBatch()));
            check(phone.acknowledgeBatch(watch.receiveBatch(last)));
            check(phone.pending() > 0);
            check(DatabaseUtils.queryNumEntries(phone.getReadableDatabase(), "batch_out_parts") == 0);
            check(DatabaseUtils.queryNumEntries(watch.getReadableDatabase(), "batch_in_parts") == 0);
        }
    }

    private void conflict(Store phone, Store watch) {
        watch.record(watch.instances().get(0), Action.COMPLETE, 0, false, System.currentTimeMillis(), false);
        phone.receive(Wire.encode(watch.snapshot(false)));
        Wire.Snapshot conflicting = phone.snapshot(false);
        conflicting.plans.get(0).version++;
        conflicting.plans.get(0).title = "冲突不可部分提交";
        conflicting.events.get(0).logical++;
        List<byte[]> pages = new ArrayList<>();
        Batch.Manifest manifest = Batch.prepare(conflicting, pages::add);
        String before = Wire.JSON.toJson(watch.plans());
        long pending = watch.pending();
        for (int index = 0; index < pages.size() - 1; index++) {
            check(!watch.receiveBatch(Batch.packet(manifest, index, pages.get(index))).committed);
        }
        try {
            int last = pages.size() - 1;
            watch.receiveBatch(Batch.packet(manifest, last, pages.get(last)));
            throw new AssertionError("Conflicting final batch committed");
        } catch (IllegalArgumentException expected) {
            check(before.equals(Wire.JSON.toJson(watch.plans())));
            check(watch.pending() == pending);
            check(DatabaseUtils.queryNumEntries(watch.getReadableDatabase(), "batch_in_parts") == pages.size() - 1);
        }
    }
}