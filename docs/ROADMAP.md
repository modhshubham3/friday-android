# Friday: the plan

Written down so the direction does not live only in a chat. Edit it when a decision changes.

## What Friday is for

A hands-free, drive-safe personal assistant that handles the phone on the user's behalf: it reads out what
matters the moment it arrives, quietly groups everything else, and gets better from the user's corrections.

## Principles (these decide every later argument)

1. **Voice first.** Nothing important may require looking at the screen. While driving, nothing may.
2. **Local first.** Personal data lives on the phone, encrypted. A cloud model sees the least possible, only when needed.
3. **Never act on a guess.** Unclear means ask; anything that sends or removes something is confirmed first.
4. **Say "done" only when it is confirmed.** (Already how alarms and calls work.)
5. **Learn from corrections, not from hunches.** Every "that mattered" or "that did not" is a label; nothing else is trusted.
6. **Cheap by default.** Free-tier quota is shared with other apps, so models are used sparingly and in batches.

## The loop

```
capture -> store -> classify -> decide -> act -> feedback -> learn
                                  |
        speak now | queue for later | put in the day-end digest | ignore
```

## Data (one encrypted database on the phone)

| Table | Holds |
|---|---|
| `events` | Every notification: time, app, sender, title, text, category, importance, what Friday did, what the user said about it |
| `senders` | Per person or app: how often they matter, "always read" / "never read" rules |
| `facts` | Things the user told Friday ("Rahul is my manager", "mummy is Maatashree") |
| `tasks` | To-dos, from the user or found in messages (confirmed first) |
| `digests` | Past day-end summaries |

Encryption: SQLCipher, key held by the Android Keystore. Retention: raw text 90 days, summaries and rules for good.
Never stored or sent: one-time passwords, and anything from apps on the user's blocklist (banking, UPI).

## Phases, in order, each useful on its own

| # | Phase | The user can then say or get |
|---|---|---|
| 1 | **Notification inbox** | "Kya aaya?" reads what matters. Everything is logged locally. Noise apps muted. |
| 2 | **Drive mode** | Starts by itself on the car's Bluetooth (or by voice/tile). New messages are read aloud, in order of importance. "Reply bolo ..." dictates an answer, Friday repeats it and asks before sending. |
| 3 | **Day-end digest** | At a chosen time: "3 log ko jawab dena hai, 2 mail kaam ke, 1 delivery aayi, baaki noise." Spoken and on screen, with the actions ready. |
| 4 | **Teach loop** | "Ye zaroori tha" / "ye mat padho". A small classifier on the phone learns from those, so the digest and the read-aloud rules get sharper every week. |
| 5 | **Mail, calendar, tasks** | Calendar read straight from the phone. Mail subjects via the notifications first, the Gmail API later. Tasks spotted in messages ("kal 5 baje call karna") offered, never added silently. |
| 6 | **Proactive** | Morning briefing, "leave now" nudges, reminders when a promised callback is due. |

Why this order: phase 1 is the data every other phase stands on, and it is useful the same day. Phase 4 needs weeks of
real labels, so it only pays off if phase 1 has been running.

## How "training" really works here

There is no model to fine-tune and none is needed. Three layers, cheapest first:

1. **Rules the user can read**: "Gauri: always read", "Swiggy: digest only", "OTP: never".
2. **A tiny classifier on the phone** (naive Bayes / logistic regression over app, sender, words, time of day), updated
   with every correction. Runs in milliseconds, costs nothing, works offline.
3. **The language model**, only for the hard cases and for writing the digest: one batched call per day, not one per message.

## Driving rules

- The phone speaks only through a connected Bluetooth device or earbuds. With none connected, Friday says who messaged,
  never what they wrote (others may be in the car).
- Short, one idea per breath; a long message becomes a one-line summary and "poora padhu?".
- Interrupt only for what is urgent; the rest waits for a pause or for "kya aaya?".
- A wake word is unreliable in a noisy car, so a hardware trigger (earbuds long press, car button) is the primary one.
- Sending always means: read it back, ask, wait for "haan".

## Android facts that shape this (check each before building on it)

- Reading other apps' notifications needs the user to switch on **Notification access**. Because Friday is installed from
  outside the Play Store, Android 13 and later also make the user allow "restricted settings" for it first.
- Replying from the notification (WhatsApp, SMS, Telegram) uses the reply action those apps attach; it works while the phone is locked.
- A message cannot be read out into the other side of a phone call; during a call Friday can only show or vibrate.
- Android 15 hides the text of one-time-password notifications from listeners, which suits the never-store rule.
- Free-tier Gemini may use what it receives to improve Google's models, so personal message text should not go there
  unless the user accepts that; the paid tier does not do this.

## Built so far (WhatsApp and Gmail first)

Phases 1 to 4 exist in a first version: the notification inbox, drive mode (read aloud, spoken replies, car Bluetooth),
the day-end summary and the teach loop. Phase 5 (Gmail API, calendar, tasks) and 6 are not started. None of it has been
tried on a phone yet; the first real messages will show what needs tuning.

## Not decided yet (defaults used until told otherwise)

- Which apps count: default is all except a blocklist (banking, UPI, authenticators).
- Digest time: 9:30 pm.
- "Baat karte karte" means while talking to Friday or driving, not during a phone call.
- What "train" means to the user beyond the teach loop above.
