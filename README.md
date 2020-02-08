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
    implementation("org.json:json:20190722")

    // Phoenix Channel Client
    implementation("ch.kuon.phoenix:channel:0.1.0")
    // ...
}
```

You should be able run this minimal example:

```kotlin

import ch.kuon.phoenix.Socket
import ch.kuon.phoenix.Channel
import ch.kuon.phoenix.Presence


fun test() {
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

    chan
    .push("hello")
    .receive("ok") { msg ->
        // sent hello and got msg back
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
