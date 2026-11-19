# Conf-Chat

A serverless peer-to-peer chat system for friends and conference chats, written in Java on top of the
[Open Chord](http://open-chord.sourceforge.net/) distributed hash table (DHT). There is no central server:
every running copy of the program is an equal peer, and peers can join or leave at any time without any
reconfiguration.

Users register, log in, find and add friends, send messages (online or offline), and hold group
conversations ("conferences"), all from a command-line interface.

## Contents

- [Features](#features)
- [How it works](#how-it-works)
- [Design decisions](#design-decisions)
- [Requirements](#requirements)
- [Install and build](#install-and-build)
- [Running it](#running-it)
- [Commands](#commands)
- [Running on several machines](#running-on-several-machines)
- [Persistence](#persistence)
- [Troubleshooting](#troubleshooting)
- [Limitations](#limitations)
- [Project layout](#project-layout)
- [Licence](#licence)

## Features

- **Accounts.** Register with a username, password and full name. Passwords can be changed.
- **Friends.** On login you see which friends are online. Search all users by words of their full name,
  send a friend request, and the other user must accept before you become friends.
- **Direct messages.** Send to any friend. An online friend receives it within about a second; an offline
  friend receives it at their next login, even if the sender has since gone offline.
- **Conferences.** Create a group chat. Any participant can add other users at any time; nobody can join
  unless added. Messages go to every participant.
- **Pure P2P.** No server. Nodes can be added or removed freely.

## How it works

Every node runs an Open Chord peer. Open Chord implements the Chord protocol: nodes and keys are hashed
onto a ring, each key is stored on the node responsible for that part of the ring, and lookups take
roughly log(N) hops. Peers talk to each other over TCP sockets (`ocsocket://host:port/`). Each value is
also replicated on neighbouring nodes, so a node leaving does not lose data.

A DHT stores values under keys and can only look up an exact key, so Conf-Chat models everything as records
under well-named keys:

| Key | Stored there |
|---|---|
| `acct:<user>` | the account (username, full name, password, version) |
| `name:<word>` | search index: one entry per word of every user's full name |
| `presence:<user>` | "I am online" heartbeats |
| `friends:<user>` | that user's friends |
| `freq:<user>` | friend requests waiting for that user to answer |
| `inbox:<user>` | messages waiting for that user |
| `confmem:<id>` | the members of a conference |
| `userconf:<user>` | the conferences a user is in |

How the main features use this:

- **Online status.** A logged-in node writes a timestamped heartbeat every 5 seconds. Someone is shown as
  online if their newest heartbeat is under 20 seconds old, so a crashed node simply times out.
- **Message delivery.** Sending a message adds it to the recipient's inbox key. Each logged-in node checks
  its own inbox every second, displays what it finds and removes it. Offline users find their messages at
  login. Sender and recipient never need to be connected to each other.
- **Search.** At registration, each word of the full name is indexed under `name:<word>`. A search looks up
  each word you type and keeps users that match all of them (whole-word, case-insensitive).
- **Conferences.** A conference is the set of `confmem:<id>` entries. Sending fans the message out to each
  participant's inbox.
- **Joining the network.** A new node looks for a running node on this machine (ports 4242-4261), or uses
  the address from `--bootstrap`. If it finds none, it creates a new network.

## Design decisions

- **Leaving the system leaves conferences.** Logging out removes you from every conference. After logging
  back in you receive nothing from them unless someone adds you again.
- **Only online users can be added to a conference**, because an offline user would be removed at their next
  login anyway.
- **Any registered user can be added to a conference**, not only friends.
- **Messages to offline friends** are delivered at next login; conference messages are not queued.
- **Security is out of scope.** Passwords are stored and compared as plain text and nothing is encrypted.
- **One session per user.** A second simultaneous login for the same user is refused.

## Requirements

- **JDK 17 or newer** (the `javac` command must work; a plain JRE is not enough).
- Windows, Linux or macOS. Developed and tested on JDK 17.

Open Chord is **bundled** in `lib/openchord.jar`, so there is nothing else to install. It is a fixed snapshot
of Open Chord 1.0.5: the original 1.0.5 download crashes when the very first node stores anything
(`... References.getPredecessor() is null`), and this build does not.

Check your Java installation:

```
java -version
javac -version
```

## Install and build

Open Chord is already included as a prebuilt library (`lib/openchord.jar`), compiled from the source in `openchord-src/`, so you only need a JDK. There is nothing else to download or install: no separate Open Chord download, no Ant and no log4j, even though the Open Chord website lists them.

> Don't replace the bundled jar with the release from the Open Chord website (http://open-chord.sourceforge.net/). That release crashes when the first node stores anything (`... References.getPredecessor() is null`), and the bundled build does not.

```
git clone <your-repo-url>
cd confchat
```

**Windows**

```
build.bat
```

**Linux / macOS**

```
mkdir -p out
javac -encoding UTF-8 -cp lib/openchord.jar -d out src/confchat/*.java
```

## Running it

Each terminal window is one node, and normally one user. For a one-machine demo, open three windows.

**Windows**

```
run.bat
```

**Linux / macOS**

```
java -cp "out:lib/openchord.jar" confchat.Main
```

1. Start the **first** node and wait until it prints `Created a new network`.
2. Start the others. They find the first one automatically and print `Joined the network`.
3. In each window type `register` (once per user), then `login`.

If Windows Firewall asks about Java, allow it on a private network.

Example session (two windows):

```
alice> find jones
  bob              Bob Jones
Use: addfriend <username>
alice> addfriend bob
Friend request sent to bob.

bob> * alice (Alice Smith) wants to be your friend. Type 'requests' to see it.
bob> accept alice
alice is now your friend.

alice> * bob accepted your friend request. You are now friends.
alice> msg bob hello!

bob> alice: hello!
bob> conf new team alice
Created conference 'team' (id 3f9a1c), members: bob
bob> say team meeting at 3
```

### Start-up options

| Option | Meaning |
|---|---|
| `--port N` | listen on this port (default: a free port between 4242 and 4261) |
| `--host IP` or `--host auto` | the address other nodes use to reach this one (default `127.0.0.1`; `auto` picks the LAN address) |
| `--bootstrap host:port` | join the network through this node (default: look on this machine) |
| `--data DIR` | where the shutdown snapshot is kept (default `confchat-data`) |

Add `-Dconfchat.debug=true` to the `java` command to print background errors.

## Commands

Before login: `register`, `login`, `quit`.

After login:

| Command | What it does |
|---|---|
| `friends` | list your friends and who is online |
| `find <words>` | search users by words of their full name |
| `addfriend <username>` | send a friend request |
| `requests` | show pending requests |
| `accept <user>` / `reject <user>` | answer a request |
| `msg <friend> <text>` | message a friend (delivered at next login if offline) |
| `conf new <name> [users...]` | create a conference (invited users must be online) |
| `conf list` | your conferences and their members |
| `conf add <conference> <user>` | add an online user |
| `say <conference> <text>` | message everyone in a conference (also `conf say ...`) |
| `conf leave <conference>` | leave a conference |
| `passwd` | change your password |
| `logout`, `quit` | log out / log out and exit |

A conference is referred to by its id or its name. Names are a single word.

## Running on several machines

All machines must be on the same network, run the same build, and allow Java through their firewall
(set the network type to Private on Windows). Start the first node with its real address, then point the
others at it:

```
# machine 1
run.bat --host 192.168.1.10 --port 4242

# machine 2
run.bat --host 192.168.1.20 --bootstrap 192.168.1.10:4242
```

Presence uses timestamps, so the machines' clocks should be reasonably in sync.

## Persistence

All data lives in memory inside the running nodes. It survives nodes leaving one at a time, because data is
replicated. When a node exits cleanly (`quit` or Ctrl+C) it writes a snapshot to
`confchat-data/snapshot.dat`. The next node that **starts a new network** from that folder restores it
(`restored N saved records`), bringing back accounts, friendships and messages waiting for offline users.
Online status and conference memberships are not restored.

- Quit nodes one at a time, a few seconds apart, for the most complete snapshot.
- A node that crashes or is killed saves nothing.
- Only the node that creates a new network reads the snapshot; nodes that join an existing one ignore it.
- To start completely fresh, close all nodes and delete `confchat-data/`.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `javac` is not recognised | You have a JRE, or the JDK is not on `PATH`. Install a JDK 17+ and open a new terminal. |
| `Cannot reach bootstrap node ...` | The address or port is wrong, the first node is not running, or a firewall blocks it. |
| Two separate networks appear | Two first nodes were started at the same moment. Start one, wait for `Created a new network`, then the rest. |
| `already logged in elsewhere` | That user is logged in on another node. If that session crashed, wait about 20 seconds. |
| `Port ... is already in use` | Another program holds the port; omit `--port` to pick a free one. |
| `No free port between 4242 and 4261` | About 20 local nodes are already running; use `--port`. |
| Everything is empty after a restart | The last node was killed rather than quit, or a node joined an existing network. See [Persistence](#persistence). |
| Operations fail for a few seconds after a node dies | The ring is repairing itself; operations are retried automatically. |

## Limitations

- Data is in memory only; if every node is killed at once, everything since the last clean exit is lost.
- A DHT only supports exact-key lookup, so user search is whole-word matching on an index (no partial words).
- Messages arrive by polling every second rather than being pushed.
- Presence relies on clocks being roughly in sync across machines.
- Registering the same username on two nodes at the exact same moment is not guarded against.
- No authentication or encryption; unsuitable for real use on an untrusted network.
- About 20 nodes per machine with the default port range.

## Project layout

```
build.bat, run.bat        Windows build and start scripts
src/confchat/
  Records.java            every record stored in the DHT, and the key names
  Dht.java                one Open Chord node: join/create, get/put/del with retries, snapshot
  ChatService.java        the application logic: accounts, friends, messages, conferences, presence
  Cli.java                the command-line interface
  Main.java               option parsing and start-up
lib/openchord.jar         bundled Open Chord (compiled from openchord-src/)
openchord-src/            Open Chord source and its licence
```

Suggested reading order: `Records` -> `Dht` -> `ChatService` -> `Cli` -> `Main`.

## Licence

Conf-Chat bundles Open Chord, which is released under the GNU General Public License (see
`openchord-src/license.txt`). Add your own licence for the Conf-Chat source here, keeping the GPL's terms
in mind for anything distributed together with Open Chord.

Open Chord is by the Distributed Systems Group, University of Bamberg:
<http://open-chord.sourceforge.net/>.
