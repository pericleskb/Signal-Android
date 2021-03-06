package org.thoughtcrime.securesms.megaphone;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Stream;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.database.model.MegaphoneRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.lock.v2.CreateKbsPinActivity;
import org.thoughtcrime.securesms.lock.v2.KbsMigrationActivity;
import org.thoughtcrime.securesms.lock.v2.PinUtil;
import org.thoughtcrime.securesms.util.FeatureFlags;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Creating a new megaphone:
 * - Add an enum to {@link Event}
 * - Return a megaphone in {@link #forRecord(Context, MegaphoneRecord)}
 * - Include the event in {@link #buildDisplayOrder()}
 *
 * Common patterns:
 * - For events that have a snooze-able recurring display schedule, use a {@link RecurringSchedule}.
 * - For events guarded by feature flags, set a {@link ForeverSchedule} with false in
 *   {@link #buildDisplayOrder()}.
 * - For events that change, return different megaphones in {@link #forRecord(Context, MegaphoneRecord)}
 *   based on whatever properties you're interested in.
 */
public final class Megaphones {

  private Megaphones() {}

  static @Nullable Megaphone getNextMegaphone(@NonNull Context context, @NonNull Map<Event, MegaphoneRecord> records) {
    long currentTime = System.currentTimeMillis();

    List<Megaphone> megaphones = Stream.of(buildDisplayOrder())
                                       .filter(e -> {
                                         MegaphoneRecord   record = Objects.requireNonNull(records.get(e.getKey()));
                                         MegaphoneSchedule schedule = e.getValue();

                                         return !record.isFinished() && schedule.shouldDisplay(record.getSeenCount(), record.getLastSeen(), record.getFirstVisible(), currentTime);
                                       })
                                       .map(Map.Entry::getKey)
                                       .map(records::get)
                                       .map(record -> Megaphones.forRecord(context, record))
                                       .toList();

    boolean hasOptional  = Stream.of(megaphones).anyMatch(m -> !m.isMandatory());
    boolean hasMandatory = Stream.of(megaphones).anyMatch(Megaphone::isMandatory);

    if (hasOptional && hasMandatory) {
      megaphones = Stream.of(megaphones).filter(Megaphone::isMandatory).toList();
    }

    if (megaphones.size() > 0) {
      return megaphones.get(0);
    } else {
      return null;
    }
  }

  /**
   * This is when you would hide certain megaphones based on {@link FeatureFlags}. You could
   * conditionally set a {@link ForeverSchedule} set to false for disabled features.
   */
  private static Map<Event, MegaphoneSchedule> buildDisplayOrder() {
    return new LinkedHashMap<Event, MegaphoneSchedule>() {{
      put(Event.REACTIONS, new ForeverSchedule(true));
      put(Event.PINS_FOR_ALL, new PinsForAllSchedule());
    }};
  }

  private static @NonNull Megaphone forRecord(@NonNull Context context, @NonNull MegaphoneRecord record) {
    switch (record.getEvent()) {
      case REACTIONS:
        return buildReactionsMegaphone();
      case PINS_FOR_ALL:
        return buildPinsForAllMegaphone(context, record);
      default:
        throw new IllegalArgumentException("Event not handled!");
    }
  }

  private static @NonNull Megaphone buildReactionsMegaphone() {
    return new Megaphone.Builder(Event.REACTIONS, Megaphone.Style.REACTIONS)
                        .setMandatory(false)
                        .build();
  }

  private static @NonNull Megaphone buildPinsForAllMegaphone(@NonNull Context context, @NonNull MegaphoneRecord record) {
    if (PinsForAllSchedule.shouldDisplayFullScreen(record.getFirstVisible(), System.currentTimeMillis())) {
      return new Megaphone.Builder(Event.PINS_FOR_ALL, Megaphone.Style.FULLSCREEN)
                          .setMandatory(true)
                          .enableSnooze(null)
                          .setOnVisibleListener((megaphone, listener) -> {
                            if (new NetworkConstraint.Factory(ApplicationDependencies.getApplication()).create().isMet()) {
                              listener.onMegaphoneNavigationRequested(KbsMigrationActivity.createIntent(), KbsMigrationActivity.REQUEST_NEW_PIN);
                            }
                          })
                          .build();
    } else {
      Megaphone.Builder builder = new Megaphone.Builder(Event.PINS_FOR_ALL, Megaphone.Style.BASIC)
                                               .setMandatory(true)
                                               .setImage(R.drawable.kbs_pin_megaphone);

      long daysRemaining = PinsForAllSchedule.getDaysRemaining(record.getFirstVisible(), System.currentTimeMillis());

      if (PinUtil.userHasPin(ApplicationDependencies.getApplication())) {
        return buildPinsForAllMegaphoneForUserWithPin(
            builder.enableSnooze((megaphone, listener) -> listener.onMegaphoneToastRequested(context.getString(R.string.KbsMegaphone__well_remind_you_later_confirming_your_pin, daysRemaining)))
        );
      } else {
        return buildPinsForAllMegaphoneForUserWithoutPin(
            builder.enableSnooze((megaphone, listener) -> listener.onMegaphoneToastRequested(context.getString(R.string.KbsMegaphone__well_remind_you_later_creating_a_pin, daysRemaining)))
        );
      }
    }
  }

  private static @NonNull Megaphone buildPinsForAllMegaphoneForUserWithPin(@NonNull Megaphone.Builder builder) {
    return builder.setTitle(R.string.KbsMegaphone__introducing_pins)
                  .setBody(R.string.KbsMegaphone__your_registration_lock_is_now_called_a_pin)
                  .setButtonText(R.string.KbsMegaphone__update_pin, (megaphone, listener) -> {
                    Intent intent = CreateKbsPinActivity.getIntentForPinChangeFromSettings(ApplicationDependencies.getApplication());

                    listener.onMegaphoneNavigationRequested(intent, CreateKbsPinActivity.REQUEST_NEW_PIN);
                  })
                  .build();
  }

  private static @NonNull Megaphone buildPinsForAllMegaphoneForUserWithoutPin(@NonNull Megaphone.Builder builder) {
    return builder.setTitle(R.string.KbsMegaphone__create_a_pin)
                  .setBody(R.string.KbsMegaphone__pins_add_another_layer_of_security_to_your_signal_account)
                  .setButtonText(R.string.KbsMegaphone__create_pin, (megaphone, listener) -> {
                    Intent intent = CreateKbsPinActivity.getIntentForPinCreate(ApplicationDependencies.getApplication());

                    listener.onMegaphoneNavigationRequested(intent, CreateKbsPinActivity.REQUEST_NEW_PIN);
                  })
                  .build();
  }

  public enum Event {
    REACTIONS("reactions"),
    PINS_FOR_ALL("pins_for_all");

    private final String key;

    Event(@NonNull String key) {
      this.key = key;
    }

    public @NonNull String getKey() {
      return key;
    }

    public static Event fromKey(@NonNull String key) {
      for (Event event : values()) {
        if (event.getKey().equals(key)) {
          return event;
        }
      }
      throw new IllegalArgumentException("No event for key: " + key);
    }

    public static boolean hasKey(@NonNull String key) {
      for (Event event : values()) {
        if (event.getKey().equals(key)) {
          return true;
        }
      }
      return false;
    }
  }
}
