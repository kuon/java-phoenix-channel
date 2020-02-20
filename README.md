# Phoenix Channel Java Client written in Kotlin

![Build CI](https://github.com/kuon/java-phoenix-channel/workflows/CI/badge.svg)

A simple client for
 [Phoenix channels](https://hexdocs.pm/phoenix/Phoenix.Channel.html).

Features:

- Written in Kotlin.
- Support Socket, Channels and Presence.
- Uses Socket serialization protocol V2 which is more compact that V1.
- Includes a phoenix mock server for testing.
- Code is modeled closely to the
   [reference JavaScript implementation](https://github.com/phoenixframework/phoenix/blob/master/assets/js/phoenix.js),
  this ensure an easy maintainability.
- Based on [nv-websocket-client](https://github.com/TakahikoKawasaki/nv-websocket-client).

## API documentation

<https://kuon.github.io/java-phoenix-channel/ch.kuon.phoenix/index.html>


## Getting started

When using gradle (Kotlin DSL):

Add the repository:

```kotlin

repositories {
    maven {
        setUrl("https://dl.bintray.com/kuon/java/")
    }
}
```

Add the following dependencies:

```kotlin
dependencies {
    // ...
    // Web Socket Client
    implementation("com.neovisionaries:nv-websocket-client:2.9")

    // Http utils to manipulate URL properly
    implementation("org.apache.httpcomponents:httpclient:4.5.11")

    // JSON handling
    implementation("com.github.openjson:openjson:1.0.11")

    // Phoenix Channel Client
    implementation("ch.kuon.phoenix:channel:0.1.2")
    // ...
}
```

You should be able run this minimal example:

```kotlin

import ch.kuon.phoenix.Socket
import ch.kuon.phoenix.Channel
import ch.kuon.phoenix.Presence


fun doSomething() {
    val url = "ws://localhost:4444/socket"
    val sd = Socket(url)

    sd.connect()

    val chan = sd.channel("auth:login")

    chan
    .join()
    .receive("ok") { msg ->
        // channel is connected
    }
    .receive("error") { msg ->
        // channel did not connected
    }
    .receive("timeout") { msg ->
        // connection timeout
    }

    chan
    .push("hello")
    .receive("ok") { msg ->
        // sent hello and got msg back
    }

}
```

## Important Notes

- API should be thread safe, but they use a naive locking mechanism (over the
  socket object).
- API can be used from main UI thread as minimal work is done on the calling
  thread.
- Callbacks can be called on any thread, be sure to take this into account.
- Callbacks mut be thread safe.
- Be sure to disconnect the socket in your cleanup code, this is not done
  automatically.

## Sockets

### Connection

A single websocket connection is established to the server and channels are
multiplexed over the single connection.

Connect to the server using the `Socket` class:


```kotlin
val opts = Socket.Options()
opts.timeout = 5_000 // socket timeout in milliseconds
opts.heartbeatIntervalMs = 10_000 // heartbeat intervall in milliseconds
opts.rejoinAfterMs = {tries -> tries * 500} // rejoin timer function
opts.reconnectAfterMs = {tries -> tries * 500} // reconnect timer function
opts.logger = {tag, msg -> com.android.Log.d(tag, msg)} // log message
opts.params = hashMap("user_token" to "supersecret") // params

// opts can be omitted for most uses
val socket = Socket("ws://myapp.com/socket", opts)
socket.connect()
```

### Hooks

Lifecycle events of the multiplexed connection can be hooked into via
`socket.onError()` and `socket.onClose()` events, ie:

```kotlin
socket.onError { System.out.println("There was an error with the connection!") }
socket.onClose { System.out.println("The connection closed!") }
```

## Channels

Channels are isolated, concurrent processes on the server that
subscribe to topics and broker events between the client and server.

To join a channel, you must provide the topic and channel params for
authorization. Here's an example chat room example where `"new_msg"`
events are listened for, messages are pushed to the server, and
the channel is joined with ok/error/timeout matches:


```kotlin
val channel = socket.channel(
    "room:123",
    JSONObject(hashMap("token" to roomToken))
)
channel.on("new_msg") { msg ->
    System.out.println("Got a message: " + msg.response.toString())
}
someTextInput.onClick {
    channel
    .push("new_msg", JSONObject(hashMap("data" to "somedata")))
    .receive("ok") { _ ->
        System.out.println("Created msg")
    }
    .receive("error") { reason ->
        System.out.println("Got an error: " + reason)
    }
    .receive("timeout") {
        System.out.println("Timeout!")
    }
}
channel
.join()
.receive("ok") { msg ->
    System.out.println("Join success, got messages: " + msg.toString())
}
.receive("error") { reason ->
    System.out.println("Failed to join because: " + reason)
}
.receive("timeout") {
    System.out.println("Join timeout!")
}
```

### Joining

When channels are created with `socket.channel(topic, params)`, `params` is
bound to the channel and sent on `join()`.

`join()` can only be called once, but channel might rejoin on timeout or other
error.

Successful joins receive an "ok" status, while unsuccessful joins
receive "error".

### Duplicate Join Subscriptions

While the client may join any number of topics on any number of channels,
the client may only hold a single subscription for each unique topic at any
given time. When attempting to create a duplicate subscription,
the server will close the existing channel, log a warning, and
spawn a new channel for the topic. The client will have their
`channel.onClose()` callbacks fired for the existing channel, and the new
channel join will have its receive hooks processed as normal.

## Pushing Messages

From the previous example, we can see that pushing messages to the server
can be done with `channel.push(eventName, payload)` and we can optionally
receive responses from the push. Additionally, we can use
`receive("timeout", callback)` to abort waiting for our other `receive` hooks
 and take action after some period of waiting. The default timeout is 10000ms.

### Hooks

For each joined channel, you can bind to `onError` and `onClose` events
to monitor the channel lifecycle, ie:

```javascript
channel.onError { System.out.println("There was an error!") }
channel.onClose { System.out.println("The was closed gracefully!") }
```

### onError hooks

`onError` hooks are invoked if the socket connection drops, or the channel
crashes on the server. In either case, a channel rejoin is attempted
automatically in an exponential backoff manner (the timer can be altered with
socket option `rejoinAfterMs`).

### onClose hooks

`onClose` hooks are invoked only in two cases:

1) The channel explicitly closed on the server.
2) The client explicitly closed, by calling `channel.leave()`

## Presence

The `Presence` object provides features for syncing presence information
from the server with the client and handling presences joining and leaving.

### Syncing state from the server

To sync presence state from the server, first instantiate an object and
pass your channel in to track lifecycle events:

```kotlin
val channel = socket.channel("some:topic")
val presence = Presence(channel)
```

Next, use the `presence.onSync` callback to react to state changes
from the server. For example, to render the list of users every time
the list changes, you could write:

```kotlin
presence.onSync {
  myRenderUsersFunction(presence.list())
}
```


### Listing Presences

`presence.list()` is used to return a list of presence information
based on the local state of metadata. By default, all presence
metadata is returned, but a `listBy` function can be supplied to
allow the client to select which metadata to use for a given presence.

For example, you may have a user online from different devices with
a metadata status of "online", but they have set themselves to "away"
on another device. In this case, the app may choose to use the "away"
status for what appears on the UI. The example below defines a `listBy`
function which prioritizes the first metadata which was registered for
each user. This could be the first tab they opened, or the first device
they came online from:

```kotlin
val listBy = { id, metas ->
    return metas.get(0)
}
val onlineUsers = presence.list(listBy)
```

### Handling individual presence join and leave events

The `presence.onJoin` and `presence.onLeave` callbacks can be used to
react to individual presences joining and leaving the app. For example:

```kotlin
val presence = Presence(channel)

// detect if user has joined for the 1st time or from another tab/device
presence.onJoin { id, current, newPres ->
  if(current != null) {
    log("user has entered for the first time", newPres)
  } else {
    log("user additional presence", newPres)
  }
}

// detect if user has left from all tabs/devices, or is still present
presence.onLeave { id, current, leftPres ->
  if(current.getMetas().length() === 0){
    log("user has left from all devices", leftPres)
  } else {
    log("user left from a device", leftPres)
  }
}

// receive presence data from server
presence.onSync {
  displayUsers(presence.list())
}
```

## Promises

The library itself do not include promises support, but you can easily integrate
a library like [kovenant](http://kovenant.komponents.nl/), like so:


```kotlin

fun connect(url: String): Promise<Socket, Exception> {
    val deferred = deferred<Socket,Exception>()

    val opt = Socket.Options()
    opt.logger = { tag, msg ->
        Log.d(tag, msg)
    }
    val socket = Socket(url, opt)

    val refs = mutableListOf<Int>()

    val openRef = socket.onOpen {
        deferred.resolve(socket)
        socket.off(refs)
    }
    val errRef = socket.onError { err ->
        deferred.reject(Exception(err))
        socket.off(refs)
    }
    refs.add(openRef)
    refs.add(errRef)

    socket.connect()

    return deferred.promise
}

fun join(
    socket: Socket,
    topic: String
): Promise<Pair<Channel, JSONObject>, Exception> {
    val deferred = deferred<Pair<Channel, JSONObject>,Exception>()
    val channel = socket.channel(topic)

    channel
    .join()
    .receive("ok") { msg ->
        deferred.resolve(Pair(channel, msg))
    }
    .receive("error") { msg ->
        deferred.reject(Exception(msg.toString()))
    }

    return deferred.promise
}

fun push(
    channel: Channel?,
    event: String,
    payload: JSONObject = JSONObject()
): Promise<Pair<Channel, JSONObject>, Exception> {

    if (channel == null) {
        return Promise.ofFail(Exception("Channel cannot be null"))
    }

    val deferred = deferred<Pair<Channel, JSONObject>,Exception>()

    channel
    .push(event, payload)
    .receive("ok") { msg ->
        deferred.resolve(Pair(channel, msg))
    }
    .receive("error") { msg ->
        deferred.reject(Exception(msg.toString()))
    }

    return deferred.promise
}

// Then it can be used like so

fun example() {
    connect(getString(R.string.endpoint_url)) bind { socket ->
        this.socket = socket
        join(socket, "some:channel")
    } bind { (channel, _) ->
        this.channel = channel
        push(channel, "somemessage")
    } bind { (channel, msg) ->
    } success {
        // ok
    } fail { err ->
        // not ok
    }
}
```


## License

Licensed under either of

 * Apache License, Version 2.0
   ([LICENSE-APACHE](LICENSE-APACHE) or http://www.apache.org/licenses/LICENSE-2.0)
 * MIT license
   ([LICENSE-MIT](LICENSE-MIT) or http://opensource.org/licenses/MIT)

at your option.

## Contribution

Unless you explicitly state otherwise, any contribution intentionally submitted
for inclusion in the work by you, as defined in the Apache-2.0 license, shall be
dual licensed as above, without any additional terms or conditions.
