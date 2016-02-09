# reactor-stream

[![Join the chat at https://gitter.im/reactor/reactor](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/reactor/reactor?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

[Reactive Extensions](http://reactivex.io). With the Reactive Stream Subscription protocol, stream can push or be pulled, synchronously or asynchronously, in a bounded way. In fact Java 8 Streams over [Reactive Streams](http://reactive-streams.org) for the JVM.

## Getting it
- Snapshot : **2.5.0.BUILD-SNAPSHOT**  ( Java 7+ required )
- Milestone : **2.5.0.M1**  ( Java 7+ required )

With Gradle from repo.spring.io or Maven Central repositories (stable releases only):
```groovy
    repositories {
      //maven { url 'http://repo.spring.io/milestone' }
      maven { url 'http://repo.spring.io/snapshot' }
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

Stream in action :
```java
Stream
    .range(1, 100_000_000)
    .doOnNext(System.out::println)
    .window(50, TimeUnit.MILLISECONDS)
    .flatMap(Stream::count)
    .groupBy(c -> c % 2 == 0)
    .flatMap(group -> 
        group.takeUntil(Mono.delay(group.key() == 0 ? 1 : 3))
    )
    .delaySubscription(Mono.delay(1))
    .retryWhen(errors -> errors.zipWith(Stream.range(1, 3)))
    .capacity(128)
    .consume(someMetrics::updateCounter);
```

### Log, Convert and Tap Streams

RxJava Observable/Single, Java 8 CompletableFuture and Java 9 Flow Publishers can be converted to Stream directly. Alternatively, the conventional "[as](http://projectreactor.io/core/docs/api/reactor/core/publisher/Flux.html#as-reactor.fn.Function-)" operator,  can easily convert to Reactive Stream Publisher implementations. 
```java
StreamTap<Tuple2<Integer, Long>> tapped = Stream.convert(Observable.range(1, 100_000_000))
                                                .log("my.category", Logger.REQUEST)
                                                .tap();
tapped.zipWith(
            Flux.interval(1)
                .as(Stream::from)
                .take(100)
        )
        .consume(System.out::println);
    
Stream.interval(1).consume(n -> someService.metric(tapped.get()));
```

### reactor.rx.Stream != java.util.stream.Stream

A [Reactor Stream](http://projectreactor.io/stream/docs/api/reactor/rx/Stream.html) is a Reactive Streams Publisher implementing [Reactive Extensions](http://reactivex.io). With the Reactive Stream Subscription protocol, a reactive Stream can push or be pulled, synchronously or asynchronously, in a bounded way. Java 8 Streams usually incur less overhead especially when operating on primitive sequences. However and fundamentally, these streams do not support eventual results or **latency**.

## Promise

A Reactive Streams Processor extending [reactor-core](http://github.com/reactor/reactor-core) Mono and supporting "hot/deferred fulfilling".

[<img src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/mono.png" width="500">](http://projectreactor.io/stream/docs/api/reactor/rx/Promise.html)

Fulfilling promise from any context:
```java
Promise<String> promise = Promise.prepare();
promise
    .doOnSuccess(someService::notify)
    .doOnError(someService::error)
    .doOnTerminate((success, error) -> doSomeCleanup())
    .subscribe();

SchedulerGroup.io().accept(() -> promise.onNext("hello!"));

String blockingResult = promise
    .map(otherService::transform)
    .doOnSuccess(otherService::notify)
    .doOnError(otherService::error)
    .get();
```

## Scheduling

```java
```

## Broadcaster

```java
```

## The Backpressure Thing

```java
SchedulerGroup io = SchedulerGroup.io();
Stream.merge(
        Stream.just(1)
            .repeat()
            .publishOn(io)
            .onBackpressureDrop(System.out::println),
        Stream.just(2)
            .repeat()
            .publishOn(io)
            .onBackpressureBlock(WaitStrategy.liteBlocking()),
        Stream.just(3)
            .repeat()
            .publishOn(io)
            .onBackpressureBuffer(10)
    )
    .take(20, TimeUnit.SECONDS)
    .capacity(32)
    .consume(slowConsumer());
```

## An Efficient Asynchronous Pipeline

Streams endure rounds of JMH testing with some nice success CPU or Memory-wise. This is the direct result of an interesting mix:
- Reactor Stream makes the most of [reactor-core](https://github.com/reactor/reactor-core) scheduling and queuing capabilities. 
- Its architecture is fully aligned and combined with the [reactive-streams-commons](https://github.com/reactor/reactor-streams-commons) research effort. 
- Streams participate into the "Stream Fusion" optimization lifecycle, thus reducing further message-passing overhead.

-------------------------------------
## Reference
http://projectreactor.io/stream/docs/reference/

## Javadoc
http://projectreactor.io/stream/docs/api/

-------------------------------------
_Powered by [Reactive Stream Commons](http://github.com/reactor/reactive-streams-commons)_

_Licensed under [Apache Software License 2.0](www.apache.org/licenses/LICENSE-2.0)_

_Sponsored by [Pivotal](http://pivotal.io)_

