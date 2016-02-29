# Reactor Stream

[![Join the chat at https://gitter.im/reactor/reactor](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/reactor/reactor?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

[Reactive Extensions](http://reactivex.io) over [Reactive Streams](http://reactive-streams.org) for the JVM.

## Getting it
- Snapshot : **2.5.0.BUILD-SNAPSHOT**  ( Java 8+ required )
- Milestone : **2.5.0.M1**  ( Java 8+ required )

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

## Fluxion

A Reactive Streams `Publisher` implementing the most common Reactive Extensions and other operators.
- Static factories on `Fluxion` allow for sequence generation from arbitrary callbacks types.
- Instance methods allows operational building, materialized on each Fluxion#subscribe()_ or Fluxion#consume()_ eventually called.

[<img src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/stream.png" width="500">](http://projectreactor.io/stream/docs/api/reactor/rx/Fluxion.html)

Fluxion in action :
```java
Fluxion
    .range(1, 100_000_000)
    .doOnNext(System.out::println)
    .window(50, TimeUnit.MILLISECONDS)
    .flatMap(Fluxion::count)
    .groupBy(c -> c % 2 == 0)
    .flatMap(group -> 
        group.takeUntil(Mono.delay(group.key() == 0 ? 1 : 3))
    )
    .delaySubscription(Mono.delay(1))
    .retryWhen(errors -> errors.zipWith(Fluxion.range(1, 3)))
    .useCapacity(128)
    .consume(someMetrics::updateCounter);
```

### Log, Convert and Tap Fluxions

RxJava Observable/Single, Java 8 CompletableFuture and Java 9 Flow Publishers can be converted to Fluxion directly. Alternatively, the conventional "[as](http://projectreactor.io/core/docs/api/reactor/core/publisher/Flux.html#as-java.util.function.Function-)" operator,  can easily convert to Reactive Stream Publisher implementations.
```java
FluxionTap<Tuple2<Integer, Long>> tapped = Fluxion.convert(Observable.range(1, 100_000_000))
                                                .log("my.category", Logger.REQUEST)
                                                .tap();
tapped.zipWith(
            Flux.interval(1)
                .as(Fluxion::from)
                .take(100)
        )
        .consume(System.out::println);
    
Fluxion.interval(1).consume(n -> someService.metric(tapped.get()));
```

### reactor.rx.Fluxion != java.util.stream.Stream

A [Reactor Fluxion](http://projectreactor.io/stream/docs/api/reactor/rx/Fluxion.html) is a Reactive Streams Publisher implementing [Reactive Extensions](http://reactivex.io). With the Reactive Stream Subscription protocol, a reactive Fluxion can push or be pulled, synchronously or asynchronously, in a bounded way. Java 8 Streams usually incur less overhead especially when operating on primitive sequences. However and fundamentally, these streams do not support eventual results or **latency**.

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
Fluxion.merge(
        Fluxion.just(1)
            .repeat()
            .publishOn(io)
            .onBackpressureDrop(System.out::println),
        Fluxion.just(2)
            .repeat()
            .publishOn(io)
            .onBackpressureBlock(WaitStrategy.liteBlocking()),
        Fluxion.just(3)
            .repeat()
            .publishOn(io)
            .onBackpressureBuffer(10)
    )
    .take(20, TimeUnit.SECONDS)
    .useCapacity(32)
    .consume(slowConsumer());
```

## An Efficient Asynchronous Pipeline

Fluxions endure rounds of JMH testing with some nice success CPU or Memory-wise. This is the direct result of an interesting mix:
- Reactor Fluxion makes the most of [reactor-core](https://github.com/reactor/reactor-core) scheduling and queuing capabilities.
- Its architecture is fully aligned and combined with the [reactive-streams-commons](https://github.com/reactor/reactive-streams-commons) research effort.
- Fluxion participate into the "Fusion" optimization lifecycle, thus reducing further message-passing overhead.

-------------------------------------
## Reference
http://projectreactor.io/stream/docs/reference/

## Javadoc
http://projectreactor.io/stream/docs/api/

-------------------------------------
_Powered by [Reactive Stream Commons](http://github.com/reactor/reactive-streams-commons)_

_Licensed under [Apache Software License 2.0](www.apache.org/licenses/LICENSE-2.0)_

_Sponsored by [Pivotal](http://pivotal.io)_

