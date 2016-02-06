# reactor-stream

[![Join the chat at https://gitter.im/reactor/reactor](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/reactor/reactor?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

[![Build Status](https://drone.io/github.com/reactor/reactorc-stream/status.png)](https://drone.io/github.com/reactor/reactor-stream/latest)

[Reactive Extensions](http://reactivex.io) over [Reactive Streams](http://reactive-streams.org) for the JVM.

## Getting it
- Snapshot : **2.5.0.BUILD-SNAPSHOT**  ( Java 7+ required )
- Milestone : **2.5.0.M1**  ( Java 7+ required )

With Gradle from repo.spring.io or Maven Central repositories (stable releases only):
```groovy
    repositories {
      //maven { url 'http://repo.spring.io/libs-release' }
      //maven { url 'http://repo.spring.io/libs-milestone' }
      maven { url 'http://repo.spring.io/libs-snapshot' }
      mavenCentral()
    }

    dependencies {
      compile "io.projectreactor:reactor-stream:2.5.0.BUILD-SNAPSHOT"
    }
```

## Stream

A Reactive Streams Publisher implementing the most common Reactive Extensions and other operators.
- Static factories on Stream allow for sequence generation from arbitrary callbacks types.
- Instance methods allows operational building, materialized on each _Stream#subscribe()_ or _Stream#consume()_ eventually called.

[<img src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/stream.png" width="500">](http://projectreactor.io/stream/docs/api/reactor/rx/Stream.html)

## Promise

A Reactive Streams Processor extending [reactor-core](http://github.com/reactor/reactor-core) Mono and supporting "hot/deferred fulfilling".

[<img src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/stream.png" width="500">](http://projectreactor.io/stream/docs/api/reactor/rx/Stream.html)

## Broadcaster

## The Backpressure Thing

## Reference
http://projectreactor.io/stream/docs/reference/

## Javadoc
http://projectreactor.io/stream/docs/api/

-------------------------------------
_Powered by [Reactive Stream Commons](http://github.com/reactor/reactive-streams-commons)_

_Licensed under [Apache Software License 2.0](www.apache.org/licenses/LICENSE-2.0)_

_Sponsored by [Pivotal](http://pivotal.io)_

