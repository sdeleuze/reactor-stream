/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.rx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.converter.DependencyUtils;
import reactor.core.flow.Fuseable;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSource;
import reactor.core.publisher.SchedulerGroup;
import reactor.core.queue.QueueSupplier;
import reactor.core.state.Backpressurable;
import reactor.core.state.Introspectable;
import reactor.core.subscriber.BlockingIterable;
import reactor.core.subscriber.SignalEmitter;
import reactor.core.subscriber.SubscriberWithContext;
import reactor.core.timer.Timer;
import reactor.core.util.Assert;
import reactor.core.util.EmptySubscription;
import reactor.core.util.Exceptions;
import reactor.core.util.Logger;
import reactor.core.util.PlatformDependent;
import reactor.core.util.ReactiveStateUtils;
import reactor.core.util.WaitStrategy;
import reactor.fn.BiConsumer;
import reactor.fn.BiFunction;
import reactor.fn.BooleanSupplier;
import reactor.fn.Consumer;
import reactor.fn.Function;
import reactor.fn.LongConsumer;
import reactor.fn.Predicate;
import reactor.fn.Supplier;
import reactor.fn.tuple.Tuple;
import reactor.fn.tuple.Tuple2;
import reactor.fn.tuple.Tuple3;
import reactor.fn.tuple.Tuple4;
import reactor.fn.tuple.Tuple5;
import reactor.fn.tuple.Tuple6;
import reactor.fn.tuple.Tuple7;
import reactor.fn.tuple.Tuple8;
import reactor.rx.subscriber.InterruptableSubscriber;
import reactor.rx.subscriber.ManualSubscriber;

/**
 * A public factory to build {@link Stream}, Streams provide for common transformations from a few structures such as
 * Iterable or Future to a Stream, in addition to provide for combinatory operations (merge, switchOnNext...).
 * <p>
 * <img width="640" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/stream.png" alt="">
 * <p>
 * Examples of use (In Java8 but would also work with Anonymous classes or Groovy Closures for instance):
 * <pre>
 * {@code
 * Stream.just(1, 2, 3).map(i -> i*2) //...
 *
 * Broadcaster<String> stream = Broadcaster.create()
 * stream.map(i -> i*2).consume(System.out::println);
 * stream.onNext("hello");
 *
 * Stream.range(1, 1_000_000)
 *  .publishOn(SchedulerGroup.io())
 *  .timestamp()
 *  .consume(System.out::println);
 *
 * Stream.interval(1)
 *  .map(i -> "left")
 *  .mergeWith(Stream.interval(2).map(i -> "right"))
 *  .consume(System.out::println);
 * 
 * Iterable<T> iterable =
 *  Flux.fromIterable(iterable)
 *  .as(Stream::from)
 *  .onBackpressureDrop()
 *  .toList()
 *  .get();
 * }
 * </pre>
 *
 * @param <O> The type of the output values
 *
 * @author Stephane Maldini
 * @since 1.1, 2.0, 2.5
 */
public abstract class Stream<O> implements Publisher<O>, Backpressurable, Introspectable {


	/**
	 * Select the fastest source who won the "ambiguous" race and emitted first onNext or onComplete or onError
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/amb.png" alt="">
	 * <p>
	 *
	 * @param sources The competing source publishers
	 * @param <T> The source type of the data sequence
	 *
	 * @return a new {@link Stream} eventually subscribed to one of the sources or empty
	 */
	public static <T> Stream<T> amb(Iterable<? extends Publisher<? extends T>> sources) {
		return from(Flux.amb(sources));
	}

	/**
	 * Select the fastest source who won the "ambiguous" race and emitted first onNext or onComplete or onError
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/amb.png" alt="">
	 * <p>
	 *
	 * @param sources The competing source publishers
	 * @param <T> The source type of the data sequence
	 *
	 * @return a new {@link Stream} eventually subscribed to one of the sources or empty
	 */
	@SuppressWarnings("varargs")
	@SafeVarargs
	public static <T> Stream<T> amb(Publisher<? extends T>... sources) {
		return from(Flux.amb(sources));
	}


	/**
	 * Build a {@link Stream} whose data are generated by the combination of the most recent published values from
	 * all publishers.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png" alt="">
	 *
	 * @param sources    The upstreams {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <T>       type of the value from sources
	 * @param <V>        The produced output after transformation by the given combinator
	 *
	 * @return a {@link Stream} based on the produced combinations
	 * @since 2.0, 2.5
	 */
	@SuppressWarnings({"unchecked", "varargs"})
	@SafeVarargs
	public static <T, V> Stream<V> combineLatest(final Function<Object[], V> combinator,
			Publisher<? extends T>... sources) {
		if (sources == null || sources.length == 0) {
			return empty();
		}

		if (sources.length == 1) {
			return from((Publisher<V>) sources[0]);
		}

		return new StreamCombineLatest<>(sources,
				combinator,
				QueueSupplier.<StreamCombineLatest.SourceAndArray>xs(),
				PlatformDependent.XS_BUFFER_SIZE);
	}

	/**
	 * Build a {@link Stream} whose data are generated by the combination of the most recent published values from
	 * all publishers.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png" alt="">
	 *
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <V> The produced output after transformation by the given combinator
	 *
	 * @return a {@link Stream} based on the produced value
	 *
	 * @since 2.5
	 */
	public static <T1, T2, V> Stream<V> combineLatest(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			BiFunction<? super T1, ? super T2, ? extends V> combinator) {
		return new StreamWithLatestFrom<>(source1, source2, combinator);
	}

	/**
	 * Build a {@link Stream} whose data are generated by the combination of the most recent published values from
	 * all publishers.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png" alt="">
	 *
	 * @param source1    The first upstream {@link Publisher} to subscribe to.
	 * @param source2    The second upstream {@link Publisher} to subscribe to.
	 * @param source3    The third upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <T1>       type of the value from source1
	 * @param <T2>       type of the value from source2
	 * @param <T3>       type of the value from source3
	 * @param <V>        The produced output after transformation by the given combinator
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, V> Stream<V> combineLatest(Publisher<? extends T1> source1,
	                                                      Publisher<? extends T2> source2,
	                                                      Publisher<? extends T3> source3,
			Function<Object[], V> combinator) {
		return combineLatest(combinator, source1, source2, source3);
	}

	/**
	 * Build a {@link Stream} whose data are generated by the combination of the most recent published values from
	 * all publishers.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png" alt="">
	 *
	 * @param source1    The first upstream {@link Publisher} to subscribe to.
	 * @param source2    The second upstream {@link Publisher} to subscribe to.
	 * @param source3    The third upstream {@link Publisher} to subscribe to.
	 * @param source4    The fourth upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <T1>       type of the value from source1
	 * @param <T2>       type of the value from source2
	 * @param <T3>       type of the value from source3
	 * @param <T4>       type of the value from source4
	 * @param <V>        The produced output after transformation by the given combinator
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4, V> Stream<V> combineLatest(Publisher<? extends T1> source1,
	                                                          Publisher<? extends T2> source2,
	                                                          Publisher<? extends T3> source3,
	                                                          Publisher<? extends T4> source4,
			Function<Object[], V> combinator) {
		return combineLatest(combinator, source1, source2, source3, source4);
	}

	/**
	 * Build a {@link Stream} whose data are generated by the combination of the most recent published values from
	 * all publishers.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png" alt="">
	 *
	 * @param source1    The first upstream {@link Publisher} to subscribe to.
	 * @param source2    The second upstream {@link Publisher} to subscribe to.
	 * @param source3    The third upstream {@link Publisher} to subscribe to.
	 * @param source4    The fourth upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <T1>       type of the value from source1
	 * @param <T2>       type of the value from source2
	 * @param <T3>       type of the value from source3
	 * @param <T4>       type of the value from source4
	 * @param <T5>       type of the value from source5
	 * @param <V>        The produced output after transformation by the given combinator
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4, T5, V> Stream<V> combineLatest(Publisher<? extends T1> source1,
	                                                              Publisher<? extends T2> source2,
	                                                              Publisher<? extends T3> source3,
	                                                              Publisher<? extends T4> source4,
	                                                              Publisher<? extends T5> source5,
			Function<Object[], V> combinator) {
		return combineLatest(combinator, source1, source2, source3, source4, source5);
	}

	/**
	 * Build a {@link Stream} whose data are generated by the combination of the most recent published values from
	 * all publishers.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png" alt="">
	 *
	 * @param source1    The first upstream {@link Publisher} to subscribe to.
	 * @param source2    The second upstream {@link Publisher} to subscribe to.
	 * @param source3    The third upstream {@link Publisher} to subscribe to.
	 * @param source4    The fourth upstream {@link Publisher} to subscribe to.
	 * @param source5    The fifth upstream {@link Publisher} to subscribe to.
	 * @param source6    The sixth upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <T1>       type of the value from source1
	 * @param <T2>       type of the value from source2
	 * @param <T3>       type of the value from source3
	 * @param <T4>       type of the value from source4
	 * @param <T5>       type of the value from source5
	 * @param <T6>       type of the value from source6
	 * @param <V>        The produced output after transformation by the given combinator
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4, T5, T6, V> Stream<V> combineLatest(Publisher<? extends T1> source1,
	                                                                  Publisher<? extends T2> source2,
	                                                                  Publisher<? extends T3> source3,
	                                                                  Publisher<? extends T4> source4,
	                                                                  Publisher<? extends T5> source5,
	                                                                  Publisher<? extends T6> source6,
			Function<Object[], V> combinator) {
		return combineLatest(combinator, source1, source2, source3, source4, source5, source6);
	}

	/**
	 * Build a {@link Stream} whose data are generated by the combination of the most recent published values from
	 * all publishers.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png" alt="">
	 *
	 * @param source1    The first upstream {@link Publisher} to subscribe to.
	 * @param source2    The second upstream {@link Publisher} to subscribe to.
	 * @param source3    The third upstream {@link Publisher} to subscribe to.
	 * @param source4    The fourth upstream {@link Publisher} to subscribe to.
	 * @param source5    The fifth upstream {@link Publisher} to subscribe to.
	 * @param source6    The sixth upstream {@link Publisher} to subscribe to.
	 * @param source7    The seventh upstream {@link Publisher} to subscribe to.
	 * @param <T1>       type of the value from source1
	 * @param <T2>       type of the value from source2
	 * @param <T3>       type of the value from source3
	 * @param <T4>       type of the value from source4
	 * @param <T5>       type of the value from source5
	 * @param <T6>       type of the value from source6
	 * @param <T7>       type of the value from source7
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <V>        The produced output after transformation by the given combinator
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4, T5, T6, T7, V> Stream<V> combineLatest(Publisher<? extends T1> source1,
	                                                                      Publisher<? extends T2> source2,
	                                                                      Publisher<? extends T3> source3,
	                                                                      Publisher<? extends T4> source4,
	                                                                      Publisher<? extends T5> source5,
	                                                                      Publisher<? extends T6> source6,
	                                                                      Publisher<? extends T7> source7,
			Function<Object[], V> combinator) {
		return combineLatest(combinator, source1, source2, source3, source4, source5, source6, source7);
	}

	/**
	 * Build a {@link Stream} whose data are generated by the combination of the most recent published values from
	 * all publishers.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png" alt="">
	 *
	 * @param sources    The list of upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <V>        The produced output after transformation by the given combinator
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0, 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <T, V> Stream<V> combineLatest(Iterable<? extends Publisher<? extends T>> sources,
			final Function<Object[], V> combinator) {
		if (sources == null) {
			return empty();
		}

		return new StreamCombineLatest<>(sources,
				combinator, QueueSupplier.<StreamCombineLatest.SourceAndArray>xs(),
				PlatformDependent.XS_BUFFER_SIZE
		);
	}

	/**
	 * Build a {@link Stream} whose data are generated by the combination of the most recent published values from
	 * all publishers.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png" alt="">
	 *
	 * @param sources    The publisher of upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <V>        The produced output after transformation by the given combinator
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0, 2.5
	 */
	public static <V> Stream<V> combineLatest(Publisher<? extends Publisher<?>> sources,
			final Function<Object[], V> combinator) {
		return from(sources).buffer()
		                    .flatMap(new Function<List<? extends Publisher<?>>, Publisher<V>>() {
					@Override
					public Publisher<V> apply(List<? extends Publisher<?>> publishers) {
						return new StreamCombineLatest<>(publishers,
								combinator,
								QueueSupplier.<StreamCombineLatest.SourceAndArray>xs(),
								PlatformDependent.XS_BUFFER_SIZE);
					}
		                    }
		);
	}

	/**
	 * Concat all sources pulled from the supplied
	 * {@link Iterator} on {@link Publisher#subscribe} from the passed {@link Iterable} until {@link Iterator#hasNext}
	 * returns false. A complete signal from each source will delimit the individual sequences and will be eventually
	 * passed to the returned Publisher.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/concat.png" alt="">
	 * <p>
	 * @param sources The {@link Publisher} of {@link Publisher} to concat
	 * @param <T> The source type of the data sequence
	 *
	 * @return a new {@link Stream} concatenating all source sequences
	 */
	public static <T> Stream<T> concat(Iterable<? extends Publisher<? extends T>> sources) {
		return new StreamConcatIterable<>(sources);
	}

	/**
	 * Concat all sources emitted as an onNext signal from a parent {@link Publisher}.
	 * A complete signal from each source will delimit the individual sequences and will be eventually
	 * passed to the returned {@link Publisher} which will stop listening if the main sequence has also completed.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/concatinner.png" alt="">
	 * <p>
	 * @param sources The {@link Publisher} of {@link Publisher} to concat
	 * @param <T> The source type of the data sequence
	 *
	 * @return a new {@link Stream} concatenating all inner sources sequences until complete or error
	 */
	public static <T> Stream<T> concat(Publisher<? extends Publisher<? extends T>> sources) {
		return from(Flux.concat(sources));
	}

	/**
	 * Concat all sources pulled from the given {@link Publisher} array.
	 * A complete signal from each source will delimit the individual sequences and will be eventually
	 * passed to the returned Publisher.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/concat.png" alt="">
	 * <p>
	 * @param sources The {@link Publisher} of {@link Publisher} to concat
	 * @param <T> The source type of the data sequence
	 *
	 * @return a new {@link Stream} concatenating all source sequences
	 */
	@SuppressWarnings("varargs")
	@SafeVarargs
	public static <T> Stream<T> concat(Publisher<? extends T>... sources) {
		return new StreamConcatArray<>(sources);
	}

	/**
	 * Try to convert an object to a {@link Stream} using available support from reactor-core given the following
	 * ordering  :
	 * <ul>
	 *     <li>null to {@link #empty()}</li>
	 *     <li>Publisher to Stream</li>
	 *     <li>Iterable to Stream</li>
	 *     <li>Iterator to Stream</li>
	 *     <li>RxJava 1 Single to Stream</li>
	 *     <li>RxJava 1 Observable to Stream</li>
	 *     <li>JDK 8 CompletableFuture to Stream</li>
	 *     <li>JDK 9 Flow.Publisher to Stream</li>
	 * </ul>
	 *
	 * @param source an object emitter to convert to a {@link Publisher}
	 * @param <T> a parameter candidate generic for the returned {@link Stream}
	 *
	 * @return a new parameterized (unchecked) converted {@link Stream}
	 */
	@SuppressWarnings("unchecked")
	public static <T> Stream<T> convert(Object source) {
		if (source == null){
			return empty();
		}
		if (source instanceof Publisher) {
			return from((Publisher<T>) source);
		}
		else if (source instanceof Iterable) {
			return fromIterable((Iterable<T>) source);
		}
		else if (source instanceof Iterator) {
			final Iterator<T> defaultValues = (Iterator<T>)source;
			if (!defaultValues.hasNext()) {
				return empty();
			}

			return create(new Consumer<SubscriberWithContext<T, Iterator<T>>>() {
				@Override
				public void accept(SubscriberWithContext<T, Iterator<T>> context) {
					if(context.context().hasNext()){
						context.onNext(context.context().next());
					}
					else{
						context.onComplete();
					}
				}
			}, new Function<Subscriber<? super T>, Iterator<T>>() {
				@Override
				public Iterator<T> apply(Subscriber<? super T> subscriber) {
					return defaultValues;
				}
			});
		}
		else {
			return (Stream<T>) from(DependencyUtils.convertToPublisher(source));
		}
	}

	/**
	 * Create a {@link Stream} reacting on each available {@link Subscriber} read derived with the passed {@link
	 * Consumer}. If a previous request is still running, avoid recursion and extend the previous request iterations.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/generateforeach.png" alt="">
	 * <p>
	 * @param requestConsumer A {@link Consumer} invoked when available read with the target subscriber
	 * @param <T> The type of the data sequence
	 *
	 * @return a new {@link Stream}
	 */
	public static <T> Stream<T> create(Consumer<SubscriberWithContext<T, Void>> requestConsumer) {
		return from(Flux.create(requestConsumer));
	}

	/**
	 * Create a {@link Stream} reacting on each available {@link Subscriber} read derived with the passed {@link
	 * Consumer}. If a previous request is still running, avoid recursion and extend the previous request iterations.
	 * The argument {@code contextFactory} is executed once by new subscriber to generate a context shared by every
	 * request calls.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/generateforeach.png" alt="">
	 * <p>
	 * @param requestConsumer A {@link Consumer} invoked when available read with the target subscriber
	 * @param contextFactory A {@link Function} called for every new subscriber returning an immutable context (IO
	 * connection...)
	 * @param <T> The type of the data sequence
	 * @param <C> The type of contextual information to be read by the requestConsumer
	 *
	 * @return a new {@link Stream}
	 */
	public static <T, C> Stream<T> create(Consumer<SubscriberWithContext<T, C>> requestConsumer,
			Function<Subscriber<? super T>, C> contextFactory) {
		return from(Flux.create(requestConsumer, contextFactory));
	}

	/**
	 * Create a {@link Stream} reacting on each available {@link Subscriber} read derived with the passed {@link
	 * Consumer}. If a previous request is still running, avoid recursion and extend the previous request iterations.
	 * The argument {@code contextFactory} is executed once by new subscriber to generate a context shared by every
	 * request calls. The argument {@code shutdownConsumer} is executed once by subscriber termination event (cancel,
	 * onComplete, onError).
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/generateforeach.png" alt="">
	 * <p>
	 * @param requestConsumer A {@link Consumer} invoked when available read with the target subscriber
	 * @param contextFactory A {@link Function} called once for every new subscriber returning an immutable context (IO
	 * connection...)
	 * @param shutdownConsumer A {@link Consumer} called once everytime a subscriber terminates: cancel, onComplete(),
	 * onError()
	 * @param <T> The type of the data sequence
	 * @param <C> The type of contextual information to be read by the requestConsumer
	 *
	 * @return a new {@link Stream}
	 */
	public static <T, C> Stream<T> create(Consumer<SubscriberWithContext<T, C>> requestConsumer,
			Function<Subscriber<? super T>, C> contextFactory,
			Consumer<C> shutdownConsumer) {
		return from(Flux.create(requestConsumer, contextFactory, shutdownConsumer));
	}

	/**
	 * Create a {@link Stream} reacting on requests with the passed {@link BiConsumer}
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/generate.png" alt="">
	 *
	 * @param requestConsumer A {@link BiConsumer} with left argument request and right argument target subscriber
	 * @param <T>             The type of the data sequence
	 * @return a Stream
	 * @since 2.0.2
	 */
	public static <T> Stream<T> generate(BiConsumer<Long, SubscriberWithContext<T, Void>> requestConsumer) {
		if (requestConsumer == null) throw new IllegalArgumentException("Supplier must be provided");
		return generate(requestConsumer, null, null);
	}

	/**
	 * Create a {@link Stream} reacting on requests with the passed {@link BiConsumer}
	 * The argument {@code contextFactory} is executed once by new subscriber to generate a context shared by every
	 * request calls.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/generate.png" alt="">
	 *
	 * @param requestConsumer A {@link BiConsumer} with left argument request and right argument target subscriber
	 * @param contextFactory  A {@link Function} called for every new subscriber returning an immutable context (IO
	 *                        connection...)
	 * @param <T>             The type of the data sequence
	 * @param <C>             The type of contextual information to be read by the requestConsumer
	 * @return a Stream
	 * @since 2.0.2
	 */
	public static <T, C> Stream<T> generate(BiConsumer<Long, SubscriberWithContext<T, C>> requestConsumer,
	                                          Function<Subscriber<? super T>, C> contextFactory) {
		return generate(requestConsumer, contextFactory, null);
	}

	/**
	 * Create a {@link Stream} reacting on requests with the passed {@link BiConsumer}. The argument {@code
	 * contextFactory} is executed once by new subscriber to generate a context shared by every request calls. The
	 * argument {@code shutdownConsumer} is executed once by subscriber termination event (cancel, onComplete,
	 * onError).
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/generate.png" alt="">
	 * <p>
	 * @param requestConsumer A {@link BiConsumer} with left argument request and right argument target subscriber
	 * @param contextFactory A {@link Function} called once for every new subscriber returning an immutable context (IO
	 * connection...)
	 * @param shutdownConsumer A {@link Consumer} called once everytime a subscriber terminates: cancel, onComplete(),
	 * onError()
	 * @param <T> The type of the data sequence
	 * @param <C> The type of contextual information to be read by the requestConsumer
	 *
	 * @return a fresh Reactive {@link Stream} publisher ready to be subscribed
	 *
	 * @since 2.0.2
	 */
	public static <T, C> Stream<T> generate(BiConsumer<Long, SubscriberWithContext<T, C>> requestConsumer,
	                                          Function<Subscriber<? super T>, C> contextFactory,
	                                          Consumer<C> shutdownConsumer) {
		return from(Flux.generate(requestConsumer, contextFactory, shutdownConsumer));
	}

	/**
	 * Supply a {@link Publisher} everytime subscribe is called on the returned stream. The passed {@link Supplier}
	 * will be invoked and it's up to the developer to choose to return a new instance of a {@link Publisher} or reuse
	 * one effecitvely behaving like {@link #from(Publisher)}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/defer.png" alt="">
	 *
	 * @param supplier the {@link Publisher} {@link Supplier} to call on subscribe
	 * @param <T>      the type of values passing through the {@link Stream}
	 *
	 * @return a deferred {@link Stream}
	 */
	public static <T> Stream<T> defer(Supplier<? extends Publisher<T>> supplier) {
		return new StreamDefer<>(supplier);
	}

	/**
	 * Create a {@link Stream} that onSubscribe and immediately onComplete without emitting any item.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/empty.png" alt="">
	 * <p>
	 * @param <T> the reified type of the target {@link Subscriber}
	 *
	 * @return an empty {@link Stream}
	 */
	@SuppressWarnings("unchecked")
	public static <T> Stream<T> empty() {
		return (Stream<T>) StreamJust.EMPTY;
	}

	/**
	 * Create a {@link Stream} that onSubscribe and immediately onError with the specified error.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/error.png" alt="">
	 * <p>
	 * @param error the error to signal to each {@link Subscriber}
	 * @param <O> the reified type of the target {@link Subscriber}
	 *
	 * @return a new failed {@link Stream}
	 */
	public static <O> Stream<O> error(Throwable error) {
		return new StreamError<O>(error);
	}

	/**
	 * Build a {@link Stream} that will only emit an error signal to any new subscriber.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/errorrequest.png" alt="">
	 *
	 * @param whenRequested if true, will onError on the first request instead of subscribe().
	 *
	 * @return a new failed {@link Stream}
	 */
	public static <O> Stream<O> error(Throwable throwable, boolean whenRequested) {
		return new StreamError<O>(throwable, whenRequested);
	}

	/**
	 * A simple decoration of the given {@link Publisher} to expose {@link Stream} API.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/from.png" alt="">
	 *
	 * @param publisher the {@link Publisher} to decorate the {@link Stream} subscriber
	 * @param <T>       the type of values passing through the {@link Stream}
	 * @return a {@link Stream} view of the passed {@link Publisher}
	 */
	@SuppressWarnings("unchecked")
	public static <T> Stream<T> from(final Publisher<? extends T> publisher) {
		if (publisher instanceof Stream) {
			return (Stream<T>) publisher;
		}

		if (publisher instanceof Supplier) {
			T t = ((Supplier<T>)publisher).get();
			if(t != null){
				return just(t);
			}
		}
		return StreamSource.wrap(publisher);
	}

	/**
	 * Create a {@link Stream} that emits the items contained in the provided {@link Iterable}.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/fromarray.png" alt="">
	 *
	 * @param values The values to {@code onNext)}
	 * @param <T>    type of the values
	 * @return a {@link Stream} from emitting array items then complete
	 */
	public static <T> Stream<T> fromArray(T[] values) {
		return from(Flux.fromArray(values));
	}

	/**
	 * Build a {@link Mono} that will only emit the result of the future and then complete.
	 * The future will be polled for an unbounded amount of time on request().
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/fromfuture.png" alt="">
	 *
	 * @param future the future to poll value from
	 * @return a new {@link Mono}
	 */
	public static <T> Mono<T> fromFuture(Future<? extends T> future) {
		return new MonoFuture<T>(future);
	}

	/**
	 * Build a {@link Mono} that will only emit the result of the future and then complete.
	 * The future will be polled for a given amount of time on request() then onError if failed to return.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/fromfuture.png" alt="">
	 *
	 * @param future the future to poll value from
	 * @return a new {@link Mono}
	 */
	public static <T> Mono<T> fromFuture(Future<? extends T> future, long time, TimeUnit unit) {
		return new MonoFuture<>(future, time, unit);
	}

	/**
	 * Create a {@link Stream} that emits the items contained in the provided {@link Iterable}.
	 * A new iterator will be created for each subscriber.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/fromiterable.png" alt="">
	 * <p>
	 * @param it the {@link Iterable} to read data from
	 * @param <T> the {@link Iterable} type to stream
	 *
	 * @return a new {@link Stream}
	 */
	public static <T> Stream<T> fromIterable(Iterable<? extends T> it) {
		return new StreamIterable<>(it);
	}

	/**
	 * A simple decoration of the given {@link Processor} to expose {@link Stream} API.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/from.png" alt="">
	 *
	 * @param processor the {@link Processor} to decorate with the {@link Stream} API
	 * @param <I>       the type of values observed by the receiving subscriber
	 * @param <O>       the type of values passing through the sending {@link Stream}
	 * @return a new {@link Stream}
	 */
	@SuppressWarnings("unchecked")
	public static <I, O> StreamProcessor<I, O> fromProcessor(final Processor<I, O> processor) {
		if (StreamProcessor.class.isAssignableFrom(processor.getClass())) {
			return (StreamProcessor<I, O>) processor;
		}
		return new StreamProcessor<>(processor, processor);
	}

	/**
	 * Create a new {@link Stream} that emits an ever incrementing long starting with 0 every N seconds on
	 * the given timer. If demand is not produced in time, an onError will be signalled. The {@link Stream} will never
	 * complete.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/interval.png" alt="">
	 * <p>
	 * @param period The number of seconds to wait before the next increment
	 *
	 * @return a new timed {@link Stream}
	 */
	public static Stream<Long> interval(long period) {
		return interval(-1L, period, TimeUnit.SECONDS, Timer.globalOrNew());
	}

	/**
	 * Build a {@link Stream} that will emit ever increasing counter from 0 after the time delay on each period.
	 * It will never complete until cancelled.
	 *
	 * @param delay  the timespan in SECONDS to wait before emitting 0l
	 * @param period the period in SECONDS before each following increment
	 * @return a new {@link Stream}
	 */
	public static Stream<Long> interval(long delay, long period) {
		return interval(delay, period, TimeUnit.SECONDS, Timer.globalOrNew());
	}

	/**
	 * Create a new {@link Stream} that emits an ever incrementing long starting with 0 every N period of time unit on
	 * the global timer. If demand is not produced in time, an onError will be signalled. The {@link Stream} will never
	 * complete.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/interval.png" alt="">
	 * <p>
	 * @param period The the time relative to given unit to wait before the next increment
	 * @param unit The unit of time
	 *
	 * @return a new timed {@link Stream}
	 */
	public static Stream<Long> interval(long period, TimeUnit unit) {
		return interval(-1L, period, unit, Timer.globalOrNew());
	}

	/**
	 * Build a {@link Stream} that will emit ever increasing counter from 0 after the subscribe call on each period.
	 * It will never complete until cancelled.
	 *
	 * @param period the period in [unit] before each following increment
	 * @param unit   the time unit
	 * @param timer  the timer to run on
	 * @return a new {@link Stream}
	 */
	public static Stream<Long> interval(long period, TimeUnit unit, Timer timer) {
		return interval(-1L, period, unit, timer);
	}

	/**
	 * Build a {@link Stream} that will emit ever increasing counter from 0 after the subscribe call on each period.
	 * It will never complete until cancelled.
	 *
	 * @param delay  the timespan in [unit] to wait before emitting 0l
	 * @param period the period in [unit] before each following increment
	 * @param unit   the time unit
	 * @return a new {@link Stream}
	 */
	public static Stream<Long> interval(long delay, long period, TimeUnit unit) {
		return interval(delay, period, unit, Timer.globalOrNew());
	}

	/**
	 * Build a {@link Stream} that will emit ever increasing counter from 0 after the time delay on each period.
	 * It will never complete until cancelled.
	 *
	 * @param delay  the timespan in [unit] to wait before emitting 0l
	 * @param period the period in [unit] before each following increment
	 * @param unit   the time unit
	 * @param timer  the timer to run on
	 * @return a new {@link Stream}
	 */
	public static Stream<Long> interval(long delay, long period, TimeUnit unit, Timer timer) {
		return new StreamInterval(TimeUnit.MILLISECONDS.convert(delay, unit), period, unit, timer);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * produced by the passed combinator function of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/join.png" alt="">
	 *
	 * @param sources the {@link Publisher} array to iterate on {@link Publisher#subscribe(Subscriber)}
	 * @param <T> the source collected type
	 *
	 * @return a zipped {@link Stream} as {@link List}
	 */
	@SuppressWarnings({"unchecked", "varargs"})
	@SafeVarargs
	public static <T> Stream<List<T>> join(Publisher<? extends T>... sources) {
		return from(Flux.zip(JOIN_FUNCTION, sources));
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * produced by the passed combinator function of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 *
	 * The {@link Iterable#iterator()} will be called on each {@link Publisher#subscribe(Subscriber)}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/join.png" alt="">
	 *
	 * @param sources the {@link Iterable} to iterate on {@link Publisher#subscribe(Subscriber)}
	 * @param <T> the produced type
	 *
	 * @return a zipped {@link Stream} as {@link List}
	 */
	@SuppressWarnings("unchecked")
	public static <T> Stream<List<T>> join(Iterable<? extends Publisher<?>> sources) {
		return zip(sources, JOIN_FUNCTION);
	}

	/**
	 * Build a {@link Stream} whom data is sourced by the passed element on subscription
	 * request. After all data is being dispatched, a complete signal will be emitted.
	 * <p>
	 *
	 * @param value1 The only value to {@code onNext()}
	 * @param <T>    type of the values
	 * @return a {@link Stream} based on the given values
	 */
	public static <T> Stream<T> just(T value1) {
		if(value1 == null){
			throw Exceptions.argumentIsNullException();
		}

		return new StreamJust<T>(value1);
	}

	/**
	 * Create a new {@link Stream} that emits the specified items and then complete.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/justn.png" alt="">
	 * <p>
	 * @param values the consecutive data objects to emit
	 * @param <T> the emitted data type
	 *
	 * @return a new {@link Stream}
	 */
	@SafeVarargs
	@SuppressWarnings({"unchecked", "varargs"})
	public static <T> Stream<T> just(T... values) {
		return from(Flux.fromArray(Objects.requireNonNull(values)));
	}

	/**
	 * Merge emitted {@link Publisher} sequences from the passed {@link Iterable} into an interleaved merged sequence.
	 * {@link Iterable#iterator()} will be called for each {@link Publisher#subscribe}.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/merge.png" alt="">
	 * <p>
	 * @param sources the {@link Iterable} to lazily iterate on {@link Publisher#subscribe(Subscriber)}
	 * @param <T> The source type of the data sequence
	 *
	 * @return a fresh Reactive {@link Stream} publisher ready to be subscribed
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T> Stream<T> merge(Iterable<? extends Publisher<? extends T>> sources) {
		return from(Flux.merge(sources));
	}

	/**
	 * Merge emitted {@link Publisher} sequences from the passed {@link Publisher} array into an interleaved merged
	 * sequence.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/merge.png" alt="">
	 * <p>
	 * @param sources the {@link Publisher} array to iterate on {@link Publisher#subscribe(Subscriber)}
	 * @param <T> The source type of the data sequence
	 *
	 * @return a fresh Reactive {@link Stream} publisher ready to be subscribed
	 * @since 2.0, 2.5
	 */
	@SafeVarargs
	@SuppressWarnings({"unchecked", "varargs"})
	public static <T> Stream<T> merge(Publisher<? extends T>... sources) {
		return from(Flux.merge(sources));
	}

	/**
	 * Merge emitted {@link Publisher} sequences by the passed {@link Publisher} into an interleaved merged sequence.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/mergeinner.png" alt="">
	 * <p>
	 * @param source a {@link Publisher} of {@link Publisher} sequence to merge
	 * @param <T> the merged type
	 *
	 * @return a merged {@link Stream}
	 */
	public static <T, E extends T> Stream<E> merge(Publisher<? extends Publisher<E>> source) {
		return from(Flux.merge(source));
	}

	/**
	 * Create a {@link Stream} that will never signal any data, error or completion signal.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/never.png" alt="">
	 * <p>
	 * @param <T> the {@link Subscriber} type target
	 *
	 * @return a never completing {@link Stream}
	 */
	@SuppressWarnings("unchecked")
	public static <T> Stream<T> never() {
		return (Stream<T>) NEVER;
	}

	/**
	 * Build a {@link Stream} that will only emit a sequence of incrementing integer from {@code start} to {@code
	 * start + count} then complete.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/range.png" alt="">
	 *
	 * @param start the first integer to be emit
	 * @param count   the number ot times to emit an increment including the first value
	 * @return a ranged {@link Stream}
	 */
	public static Stream<Integer> range(int start, int count) {
		if(count == 1){
			return just(start);
		}
		if(count == 0){
			return empty();
		}
		return new StreamRange(start, count);
	}

	/**
	 * Build a {@link StreamProcessor} whose data are emitted by the most recent emitted {@link Publisher}.
	 * The {@link Stream} will complete once both the publishers source and the last switched to {@link Publisher} have
	 * completed.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/switchonnext.png" alt="">
	 *
	 * @param <T> the produced type
	 * @return a {@link StreamProcessor} accepting publishers and producing T
	 * @since 2.0, 2.5
	 */
	public static <T> StreamProcessor<Publisher<? extends T>, T> switchOnNext() {
		Processor<Publisher<? extends T>, Publisher<? extends T>> emitter = EmitterProcessor.replay();
		StreamProcessor<Publisher<? extends T>, T> p = new StreamProcessor<>(emitter, switchOnNext(emitter));
		p.onSubscribe(EmptySubscription.INSTANCE);
		return p;
	}

	/**
	 * Build a {@link StreamProcessor} whose data are emitted by the most recent emitted {@link Publisher}.
	 * The {@link Stream} will complete once both the publishers source and the last switched to {@link Publisher} have
	 * completed.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/switchonnext.png" alt="">
	 *
	 * @param mergedPublishers The {@link Publisher} of switching {@link Publisher} to subscribe to.
	 * @param <T> the produced type
	 * @return a {@link StreamProcessor} accepting publishers and producing T
	 * @since 2.0, 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <T> Stream<T> switchOnNext(
	  Publisher<Publisher<? extends T>> mergedPublishers) {
		return new StreamSwitchMap<>(mergedPublishers,
				IDENTITY_FUNCTION,
				QueueSupplier.xs(),
				PlatformDependent.XS_BUFFER_SIZE);
	}

	/**
	 * Uses a resource, generated by a supplier for each individual Subscriber,
	 * while streaming the values from a
	 * Publisher derived from the same resource and makes sure the resource is released
	 * if the sequence terminates or the Subscriber cancels.
	 * <p>
	 * Eager resource cleanup happens just before the source termination and exceptions
	 * raised by the cleanup Consumer may override the terminal even.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/using.png" alt="">
	 *
	 * @param resourceSupplier a {@link Callable} that is called on subscribe
	 * @param sourceSupplier a {@link Publisher} factory derived from the supplied resource
	 * @param resourceCleanup invoked on completion
	 * @param <T> emitted type
	 * @param <D> resource type
	 * @return new {@link Stream}
	 */
	public static <T, D> Stream<T> using(Callable<? extends D> resourceSupplier, Function<? super D, ? extends
			Publisher<? extends T>> sourceSupplier, Consumer<? super D> resourceCleanup) {
		return using(resourceSupplier, sourceSupplier, resourceCleanup, true);
	}

	/**
	 *
	 * Uses a resource, generated by a supplier for each individual Subscriber,
	 * while streaming the values from a
	 * Publisher derived from the same resource and makes sure the resource is released
	 * if the sequence terminates or the Subscriber cancels.
	 * <p>
	 * <ul>
	 * <li>Eager resource cleanup happens just before the source termination and exceptions
	 * raised by the cleanup Consumer may override the terminal even.</li>
	 * <li>Non-eager cleanup will drop any exception.</li>
	 * </ul>
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/using.png" alt="">
	 *
	 * @param resourceSupplier a {@link Callable} that is called on subscribe
	 * @param sourceSupplier a {@link Publisher} factory derived from the supplied resource
	 * @param resourceCleanup invoked on completion
	 * @param eager true to clean before terminating downstream subscribers
	 * @param <T> emitted type
	 * @param <D> resource type
	 * @return new Stream
	 */
	public static <T, D> Stream<T> using(Callable<? extends D> resourceSupplier, Function<? super D, ? extends
			Publisher<? extends T>> sourceSupplier, Consumer<? super D> resourceCleanup, boolean eager) {
		return new StreamUsing<>(resourceSupplier, sourceSupplier, resourceCleanup, eager);
	}

	/**
	 * Create a {@link Stream} reacting on subscribe with the passed {@link Consumer}. The argument {@code
	 * sessionConsumer} is executed once by new subscriber to generate a {@link SignalEmitter} context ready to accept
	 * signals.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/yield.png" alt="">
	 * <p>
	 * @param sessionConsumer A {@link Consumer} called once everytime a subscriber subscribes
	 * @param <T> The type of the data sequence
	 *
	 * @return a fresh Reactive {@link Stream} publisher ready to be subscribed
	 */
	public static <T> Stream<T> yield(Consumer<? super SignalEmitter<T>> sessionConsumer) {
		return from(Flux.yield(sessionConsumer));
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * produced by the passed combinator function of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zip.png" alt="">
	 * <p>
	 *
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 * value to signal downstream
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <V> The produced output after transformation by the combinator
	 *
	 * @return a zipped {@link Stream}
	 *
	 * @since 2.0
	 */
	public static <T1, T2, V> Stream<V> zip(Publisher<? extends T1> source1,
	                                        Publisher<? extends T2> source2,
	                                        BiFunction<? super T1, ? super T2, ? extends V> combinator) {
		return from(Flux.zip(source1, source2, combinator));
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipt.png" alt="">
	 * <p>
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 *
	 * @return a zipped {@link Stream}
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2> Stream<Tuple2<T1, T2>> zip(Publisher<? extends T1> source1,
	                                                  Publisher<? extends T2> source2) {
		return from(Flux.zip(source1, source2));
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipt.png" alt="">
	 * <p>
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param source3 The third upstream {@link Publisher} to subscribe to.
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <T3> type of the value from source3
	 *
	 * @return a zipped {@link Stream}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3> Stream<Tuple3<T1, T2, T3>> zip(Publisher<? extends T1> source1,
												              Publisher<? extends T2> source2,
												              Publisher<? extends T3> source3) {
		return from(Flux.zip(source1, source2, source3));
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipt.png" alt="">
	 * <p>
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param source3 The third upstream {@link Publisher} to subscribe to.
	 * @param source4 The fourth upstream {@link Publisher} to subscribe to.
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <T3> type of the value from source3
	 * @param <T4> type of the value from source4
	 *
	 * @return a zipped {@link Stream}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4> Stream<Tuple4<T1, T2, T3, T4>> zip(Publisher<? extends T1> source1,
													                  Publisher<? extends T2> source2,
													                  Publisher<? extends T3> source3,
													                  Publisher<? extends T4> source4) {
		return from(Flux.zip(source1, source2, source3, source4));
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipt.png" alt="">
	 * <p>
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param source3 The third upstream {@link Publisher} to subscribe to.
	 * @param source4 The fourth upstream {@link Publisher} to subscribe to.
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <T3> type of the value from source3
	 * @param <T4> type of the value from source4
	 * @param <T5> type of the value from source5
	 *
	 * @return a zipped {@link Stream}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4, T5> Stream<Tuple5<T1, T2, T3, T4, T5>> zip(Publisher<? extends T1> source1,
														                      Publisher<? extends T2> source2,
														                      Publisher<? extends T3> source3,
														                      Publisher<? extends T4> source4,
														                      Publisher<? extends T5> source5) {
		return from(Flux.zip(source1, source2, source3, source4, source5));
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipt.png" alt="">
	 * <p>
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param source3 The third upstream {@link Publisher} to subscribe to.
	 * @param source4 The fourth upstream {@link Publisher} to subscribe to.
	 * @param source5 The fifth upstream {@link Publisher} to subscribe to.
	 * @param source6 The sixth upstream {@link Publisher} to subscribe to.
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <T3> type of the value from source3
	 * @param <T4> type of the value from source4
	 * @param <T5> type of the value from source5
	 * @param <T6> type of the value from source6
	 *
	 * @return a zipped {@link Stream}
	 *
	 * @since 2.5
	 */
	public static <T1, T2, T3, T4, T5, T6> Stream<Tuple6<T1, T2, T3, T4, T5, T6>> zip(Publisher<? extends T1> source1,
															                          Publisher<? extends T2> source2,
															                          Publisher<? extends T3> source3,
															                          Publisher<? extends T4> source4,
															                          Publisher<? extends T5> source5,
															                          Publisher<? extends T6> source6) {
		return from(Flux.zip(source1, source2, source3, source4, source5, source6));
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipt.png" alt="">
	 * <p>
	 * @param source1    The first upstream {@link Publisher} to subscribe to.
	 * @param source2    The second upstream {@link Publisher} to subscribe to.
	 * @param source3    The third upstream {@link Publisher} to subscribe to.
	 * @param source4    The fourth upstream {@link Publisher} to subscribe to.
	 * @param source5    The fifth upstream {@link Publisher} to subscribe to.
	 * @param source6    The sixth upstream {@link Publisher} to subscribe to.
	 * @param source7    The seventh upstream {@link Publisher} to subscribe to.
	 * @param <T1>       type of the value from source1
	 * @param <T2>       type of the value from source2
	 * @param <T3>       type of the value from source3
	 * @param <T4>       type of the value from source4
	 * @param <T5>       type of the value from source5
	 * @param <T6>       type of the value from source6
	 * @param <T7>       type of the value from source7
	 * @return a {@link Stream} based on the produced value
	 *
	 * @since 2.5
	 */
	public static <T1, T2, T3, T4, T5, T6, T7> Stream<Tuple7<T1, T2, T3, T4, T5, T6, T7>> zip(
			Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			Publisher<? extends T3> source3,
			Publisher<? extends T4> source4,
			Publisher<? extends T5> source5,
			Publisher<? extends T6> source6,
			Publisher<? extends T7> source7) {
		return from(Flux.zip(Tuple.<T1, T2, T3, T4, T5, T6, T7>fn7(), source1, source2, source3, source4, source5,
				source6,
				source7));
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipt.png" alt="">
	 * <p>
	 * @param source1    The first upstream {@link Publisher} to subscribe to.
	 * @param source2    The second upstream {@link Publisher} to subscribe to.
	 * @param source3    The third upstream {@link Publisher} to subscribe to.
	 * @param source4    The fourth upstream {@link Publisher} to subscribe to.
	 * @param source5    The fifth upstream {@link Publisher} to subscribe to.
	 * @param source6    The sixth upstream {@link Publisher} to subscribe to.
	 * @param source7    The seventh upstream {@link Publisher} to subscribe to.
	 * @param source8    The eight upstream {@link Publisher} to subscribe to.
	 * @param <T1>       type of the value from source1
	 * @param <T2>       type of the value from source2
	 * @param <T3>       type of the value from source3
	 * @param <T4>       type of the value from source4
	 * @param <T5>       type of the value from source5
	 * @param <T6>       type of the value from source6
	 * @param <T7>       type of the value from source7
	 * @param <T8>       type of the value from source7
	 * @return a {@link Stream} based on the produced value
	 *
	 * @since 2.5
	 */
	public static <T1, T2, T3, T4, T5, T6, T7, T8> Stream<Tuple8<T1, T2, T3, T4, T5, T6, T7, T8>> zip(
			Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			Publisher<? extends T3> source3,
			Publisher<? extends T4> source4,
			Publisher<? extends T5> source5,
			Publisher<? extends T6> source6,
			Publisher<? extends T7> source7,
			Publisher<? extends T8> source8) {
		return from(Flux.zip(Tuple.<T1, T2, T3, T4, T5, T6, T7, T8>fn8(), source1, source2, source3, source4, source5,
				source6, source7,
				source8));
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * produced by the passed combinator function of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 *
	 * The {@link Iterable#iterator()} will be called on each {@link Publisher#subscribe(Subscriber)}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zip.png" alt="">
	 *
	 * @param sources the {@link Iterable} to iterate on {@link Publisher#subscribe(Subscriber)}
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <V> the combined produced type
	 * @param <TUPLE>    The type of tuple to use that must match source Publishers type
	 *
	 * @return a zipped {@link Stream}
	 *
	 * @since 2.0
	 */
	public static <TUPLE extends Tuple, V> Stream<V> zip(Iterable<? extends Publisher<?>> sources,
			final Function<? super TUPLE, ? extends V> combinator) {
		return from(Flux.zip(sources, new Function<Object[], V>() {
			@Override
			@SuppressWarnings("unchecked")
			public V apply(Object[] tuple) {
				return combinator.apply((TUPLE)Tuple.of(tuple));
			}
		}));
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * of the most recent items emitted by each source until any of them completes. Errors will immediately be
	 * forwarded.
	 * The {@link Iterable#iterator()} will be called on each {@link Publisher#subscribe(Subscriber)}.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipt.png" alt="">
	 * <p>
	 * @param sources the {@link Iterable} to iterate on {@link Publisher#subscribe(Subscriber)}
	 * @param <TUPLE>    The type of tuple to use that must match source Publishers type
	 *
	 * @return a zipped {@link Stream}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <TUPLE extends Tuple> Stream<TUPLE> zip(Iterable<? extends Publisher<?>> sources) {
		return from((Publisher<TUPLE>) Flux.zip(sources));
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The {@link Publisher} of {@link Publisher} will
	 * accumulate into a list until completion before starting zip operation.
	 * The operator will forward all combinations
	 * of the most recent items emitted by each source until any of them completes. Errors will immediately be
	 * forwarded.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipt.png" alt="">
	 *
	 * @param sources    The publisher of upstream {@link Publisher} to subscribe to.
	 * @return a {@link Stream} based on the produced value
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public static Stream<Tuple> zip(Publisher<? extends Publisher<?>> sources) {
		return zip(sources, (Function<Tuple, Tuple>) IDENTITY_FUNCTION);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The {@link Publisher} of {@link Publisher} will
	 * accumulate into a list until completion before starting zip operation.
	 * The operator will forward all combinations of the most recent items emitted by each published source until any
	 * of them completes. Errors will immediately be
	 * forwarded.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zip.png" alt="">
	 *
	 * @param sources    The publisher of upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <V>        The produced output after transformation by the given combinator
	 * @return a {@link Stream} based on the produced value
	 *
	 * @since 2.0
	 */
	public static <TUPLE extends Tuple, V> Stream<V> zip(
	  Publisher<? extends Publisher<?>> sources,
	  final Function<? super TUPLE, ? extends V> combinator) {

		return from(sources).buffer()
		                    .flatMap(new Function<List<? extends Publisher<?>>, Publisher<V>>() {
			@Override
			@SuppressWarnings("unchecked")
			public Publisher<V> apply(List<? extends Publisher<?>> publishers) {
				return Flux.zip(Tuple.fnAny((Function<Tuple, V>)combinator), publishers.toArray(new Publisher[publishers
						.size()]));
			}
		});
	}

	protected Stream() {
	}

	/**
	 * Return a {@code Mono<Void>} that completes when this {@link Stream} completes.
	 * This will actively ignore the sequence and only replay completion or error signals.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/after.png" alt="">
	 * <p>
	 * @return a new {@link Mono}
	 */
	public final Mono<Void> after() {
		return Mono.empty(this);
	}

	/**
	 * Return a {@link Stream} that emits the sequence of the supplied {@link Publisher} when this {@link Stream}
	 * onComplete or onError.
	 * If an error occur, append after the supplied {@link Publisher} is terminated.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/afters.png" alt="">
	 *
	 * @param afterSupplier a {@link Supplier} of {@link Publisher} to emit from after termination
	 * @param <V> the supplied produced type
	 *
	 * @return a new {@link Stream} emitting eventually from the supplied {@link Publisher}
	 */
	@SuppressWarnings("unchecked")
	public final <V> Stream<V> after(final Supplier<? extends Publisher<V>> afterSupplier) {
		return StreamSource.wrap(Flux.flatMap(
				Flux.mapSignal(after(), null, new Function<Throwable, Publisher<V>>() {
					@Override
					public Publisher<V> apply(Throwable throwable) {
						return concat(afterSupplier.get(), Stream.<V>error(throwable));
					}
				}, afterSupplier),
				IDENTITY_FUNCTION, PlatformDependent.SMALL_BUFFER_SIZE, 32, false));
	}

	/**
	 *
	 * Emits a single boolean true if all values of this sequence match
	 * the {@link Predicate}.
	 * <p>
	 * The implementation uses short-circuit logic and completes with false if
	 * the predicate doesn't match a value.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/all.png" alt="">
	 *
	 * @param predicate the {@link Predicate} to match all emitted items
	 *
	 * @return a {@link Mono} of all evaluations
	 */
	public final Mono<Boolean> all(Predicate<? super O> predicate) {
		return new MonoAll<>(this, predicate);
	}

	/**
	 * Emit from the fastest first sequence between this publisher and the given publisher
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/amb.png" alt="">
	 * <p>
	 * @param other the {@link Publisher} to race with
	 *
	 * @return the fastest sequence
	 */
	public final Stream<O> ambWith(final Publisher<? extends O> other) {
		return StreamSource.wrap(Flux.amb(this, other));
	}

	/**
	 * Immediately apply the given transformation to this {@link Stream} in order to generate a target {@link Publisher} type.
	 *
	 * {@code stream.as(Mono::from).subscribe(Subscribers.unbounded()) }
	 *
	 * @param transformer the {@link Function} to immediately map this {@link Stream} into a target {@link Publisher}
	 * instance.
	 * @param <P> the returned {@link Publisher} sequence type
	 *
	 * @return a new {@link Stream}
	 */
	public final <V, P extends Publisher<V>> P as(Function<? super Stream<O>, P> transformer) {
		return transformer.apply(this);
	}


	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@link Stream} on complete
	 * only.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffer.png" alt="">
	 *
	 * @return a new {@link Stream} of at most one {@link List}
	 */
	public final Stream<List<O>> buffer() {
		return buffer(Integer.MAX_VALUE);
	}

	/**
	 * Collect incoming values into multiple {@link List} buckets that will be pushed into the returned {@link Stream}
	 * when the given max size is reached or onComplete is received.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffersize.png" alt="">
	 *
	 * @param maxSize the maximum collected size
	 *
	 * @return a new {@link Stream} of {@link List}
	 */
	@SuppressWarnings("unchecked")
	public final Stream<List<O>> buffer(final int maxSize) {
		return new StreamBuffer<>(this, maxSize, (Supplier<List<O>>) LIST_SUPPLIER);
	}

	/**
	 * Collect incoming values into multiple {@link List} that will be pushed into the returned {@link Stream} when
	 * the given max size is reached or onComplete is received. A new container {@link List} will be created every
	 * given skip count.
	 *
	 * <p>
	 * When Skip > Max Size : dropping buffers
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffersizeskip.png" alt="">
	 * <p>
	 * When Skip < Max Size : overlapping buffers
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffersizeskipover.png" alt="">
	 * <p>
	 * When Skip == Max Size : exact buffers
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffersize.png" alt="">
	 * @param skip the number of items to skip before creating a new bucket
	 * @param maxSize the max collected size
	 *
	 * @return a new {@link Stream} of possibly overlapped or gapped {@link List}
	 */
	@SuppressWarnings("unchecked")
	public final Stream<List<O>> buffer(final int maxSize, final int skip) {
		return new StreamBuffer<>(this, maxSize, skip, LIST_SUPPLIER);
	}

	/**
	 * Collect incoming values into multiple {@link List} delimited by the given {@link Publisher} signals.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/bufferboundary.png" alt="">
	 *
	 * @param other the other {@link Publisher}  to subscribe to for emiting and recycling receiving bucket
	 *
	 * @return a new {@link Stream} of {@link List} delimited by a {@link Publisher}
	 */
	@SuppressWarnings("unchecked")
	public final Stream<List<O>> buffer(final Publisher<?> other) {
		return new StreamBufferBoundary<>(this, other, LIST_SUPPLIER);
	}

	/**
	 * Collect incoming values into multiple {@link List} delimited by the given {@link Publisher} signals.
	 * Each {@link List} bucket will last until the mapped {@link Publisher} receiving the boundary signal emits,
	 * thus releasing the bucket to the returned {@link Stream}.
	 *
	 * <p>
	 * When Open signal is strictly not overlapping Close signal : dropping buffers
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/bufferopenclose.png" alt="">
	 * <p>
	 * When Open signal is strictly more frequent than Close signal : overlapping buffers
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/bufferopencloseover.png" alt="">
	 * <p>
	 * When Open signal is exactly coordinated with Close signal : exact buffers
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/bufferboundary.png" alt="">
	 *
	 * @param bucketOpening the {@link Publisher} to subscribe to for creating new receiving bucket
	 * signals.
	 * @param closeSupplier the {@link Supplier} to provide a {@link Publisher} to subscribe to for emitting relative
	 * bucket.
	 *
	 * @return a new
	 * {@link Stream} of {@link List} delimited by an opening {@link Publisher} and a relative closing {@link Publisher}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public final <U, V> Stream<List<O>> buffer(final Publisher<U> bucketOpening,
			final Function<? super U, ? extends Publisher<V>> closeSupplier) {

		return new StreamBufferStartEnd<>(this, bucketOpening, closeSupplier, LIST_SUPPLIER,
				QueueSupplier.<List<O>>xs());
	}

	/**
	 * Collect incoming values into multiple {@link List} that will be pushed into the returned {@link Stream} every
	 * timespan.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespan.png" alt="">
	 *
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit the time unit
	 *
	 * @return a new {@link Stream} of {@link List} delimited by the given period
	 */
	public final Stream<List<O>> buffer(long timespan, TimeUnit unit) {
		return buffer(timespan, unit, getTimer());
	}

	/**
	 * Collect incoming values into multiple {@link List} that will be pushed into the returned {@link Stream} every
	 * timespan.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespan.png" alt="">
	 *
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit the time unit
	 * @param timer the {@link Timer} to schedule on
	 *
	 * @return a new {@link Stream} of {@link List} delimited by the given period
	 */
	public final Stream<List<O>> buffer(long timespan, TimeUnit unit, Timer timer) {
		return buffer(interval(timespan, unit, timer));
	}

	/**
	 * Collect incoming values into multiple {@link List} delimited by the given {@code timeshift} period.
	 * Each {@link List} bucket will last until the {@code timespan} has elapsed,
	 * thus releasing the bucket to the returned {@link Stream}.
	 *
	 * <p>
	 * When timeshift > timestamp : dropping buffers
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimeshift.png" alt="">
	 * <p>
	 * When timeshift < timestamp : overlapping buffers
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimeshiftover.png" alt="">
	 * <p>
	 * When timeshift == timestamp : exact buffers
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespan.png" alt="">
	 *
	 * @param timespan the period in unit to use to release buffered lists
	 * @param timeshift the period in unit to use to create a new bucket
	 * @param unit the time unit
	 *
	 * @return a new {@link Stream} of {@link List} delimited by the given period timeshift and sized by timespan
	 */
	public final Stream<List<O>> buffer(final long timespan, final long timeshift, final TimeUnit unit) {
		return buffer(timespan, timeshift, unit, getTimer());
	}

	/**
	 * Collect incoming values into multiple {@link List} delimited by the given {@code timeshift} period.
	 * Each {@link List} bucket will last until the {@code timespan} has elapsed,
	 * thus releasing the bucket to the returned {@link Stream}.
	 *
	 * <p>
	 * When timeshift > timestamp : dropping buffers
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimeshift.png" alt="">
	 * <p>
	 * When timeshift < timestamp : overlapping buffers
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimeshiftover.png" alt="">
	 * <p>
	 * When timeshift == timestamp : exact buffers
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespan.png" alt="">
	 *
	 * @param timespan the period in unit to use to release buffered lists
	 * @param timeshift the period in unit to use to create a new bucket
	 * @param unit the time unit
	 * @param timer the {@link Timer} to run on
	 *
	 * @return a new {@link Stream} of {@link List} delimited by the given period timeshift and sized by timespan

	 */
	public final Stream<List<O>> buffer(final long timespan,
			final long timeshift,
			final TimeUnit unit,
			final Timer timer) {
		if (timespan == timeshift) {
			return buffer(timespan, unit, timer);
		}
		return buffer(interval(0L, timeshift, unit, timer), new Function<Long, Publisher<Long>>() {
			@Override
			public Publisher<Long> apply(Long aLong) {
				return Mono.delay(timespan, unit, timer);
			}
		});
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@link Stream} every timespan
	 * OR maxSize items.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespansize.png" alt="">

	 *
	 * @param maxSize the max collected size
	 * @param timespan the timeout in unit to use to release a buffered list
	 * @param unit the time unit
	 *
	 * @return a new {@link Stream} of {@link List} delimited by given size or a given period timeout
	 */
	public final Stream<List<O>> buffer(int maxSize, long timespan, TimeUnit unit) {
		return buffer(maxSize, timespan, unit, getTimer());
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@link Stream} every timespan
	 * OR maxSize items
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespansize.png" alt="">
	 *
	 * @param maxSize the max collected size
	 * @param timespan the timeout in unit to use to release a buffered list
	 * @param unit the time unit
	 * @param timer the {@link Timer} to run on
	 *
	 * @return a new {@link Stream} of {@link List} delimited by given size or a given period timeout
	 */
	public final Stream<List<O>> buffer(final int maxSize,
			final long timespan,
			final TimeUnit unit,
			final Timer timer) {
		return new StreamBufferTimeOrSize<>(this, maxSize, timespan, unit, timer);
	}

	/**
	 * Stage incoming values into a {@link java.util.PriorityQueue} that will be re-ordered and signaled to the
	 * returned {@link Stream} when sequence is complete. PriorityQueue will use the {@link Comparable} interface from
	 * an incoming data signal. Due to it's unbounded nature (must accumulate in priority queue before emitting all
	 * sequence), this operator should not be used on hot or large volume sequences.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffersort.png" alt="">
	 *
	 * @return a new {@link Stream} whose values re-ordered using a {@link PriorityQueue}.
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> bufferSort() {
		return bufferSort(null);
	}

	/**
	 * Stage incoming values into a {@link java.util.PriorityQueue} that will be re-ordered and signaled to the
	 * returned fresh {@link Mono}. PriorityQueue will use the {@link Comparable} interface from an incoming data signal.
	 *  Due to it's unbounded nature (must accumulate in priority queue before emitting all
	 * sequence), this operator should not be used on hot or large volume sequences.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffersort.png" alt="">
	 *
	 * @param comparator A {@link Comparator} to evaluate incoming data
	 *
	 * @return a new {@link Stream} whose values re-ordered using a {@link PriorityQueue}.
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> bufferSort(final Comparator<? super O> comparator) {
		return StreamSource.wrap(collect(new Supplier<PriorityQueue<O>>() {
			@Override
			public PriorityQueue<O> get() {
				if (comparator == null) {
					return new PriorityQueue<>();
				}
				else {
					return new PriorityQueue<>(PlatformDependent.MEDIUM_BUFFER_SIZE, comparator);
				}
			}
		}, new BiConsumer<PriorityQueue<O>, O>() {
			@Override
			public void accept(PriorityQueue<O> e, O o) {
				e.add(o);
			}
		}).flatMap(new Function<PriorityQueue<O>, Publisher<? extends O>>() {
			@Override
			public Publisher<? extends O> apply(final PriorityQueue<O> os) {
				return fromIterable(new Iterable<O>() {

					final Iterator<O> it = new Iterator<O>() {
						@Override
						public boolean hasNext() {
							return !os.isEmpty();
						}

						@Override
						public O next() {
							return os.poll();
						}

						@Override
						public void remove() {

						}
					};

					@Override
					public Iterator<O> iterator() {
						return it;
					}
				});
			}
		}));
	}

	/**
	 * Turn this {@link Stream} into a hot source and cache last emitted signals for further {@link Subscriber}.
	 * Will retain up to {@link PlatformDependent#SMALL_BUFFER_SIZE} onNext signals. Completion and Error will also be
	 * replayed.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/cache.png" alt="">
	 *
	 * @return a new cachable {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> cache() {
		return cache(PlatformDependent.SMALL_BUFFER_SIZE);
	}

	/**
	 * Turn this {@link Stream} into a hot source and cache last emitted signals for further {@link Subscriber}.
	 * Will retain up to the given history size onNext signals. Completion and Error will also be
	 * replayed.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/cache.png" alt="">
	 *
	 * @param history number of events retained in history excluding complete and error
	 *
	 * @return a new cachable {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> cache(int history) {
		return multicast(EmitterProcessor.<O>replay(history)).autoConnect();
	}

	/**
	 * Hint {@link Subscriber} to this {@link Stream} a preferred available capacity should be used.
	 * {@link #toIterable()} can for instance use introspect this value to supply an appropriate queueing strategy.
	 *
	 * @param capacity the maximum capacity (in flight onNext) the return {@link Publisher} should expose
	 *
	 * @return a bounded {@link Stream}
	 */
	public Stream<O> capacity(final long capacity) {
		if (capacity == getCapacity()) {
			return this;
		}

		return new StreamSource<O, O>(this) {
			@Override
			public long getCapacity() {
				return capacity;
			}

			@Override
			public String getName() {
				return "capacitySetup";
			}
		};
	}

	/**
	 * Cast the current {@link Stream} produced type into a target produced type.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/cast.png" alt="">
	 *
	 * @param <E> the {@link Stream} output type
	 *
	 * @return a casted {link Stream}
	 *
	 * @since 2.0
	 */
	@SuppressWarnings({"unchecked", "unused"})
	public final <E> Stream<E> cast(final Class<E> stream) {
		return (Stream<E>) this;
	}

	/**
	 * Collect the {@link Stream} sequence with the given collector and supplied container on subscribe.
	 * The collected result will be emitted when this sequence completes.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/collect.png" alt="">

	 *
	 * @param <E> the {@link Stream} collected container type
	 *
	 * @return a Mono sequence of the collected value on complete
	 *
	 * @since 2.5
	 */
	public final <E> Mono<E> collect(Supplier<E> containerSupplier, BiConsumer<E, ? super O> collector) {
		return new MonoCollect<>(this, containerSupplier, collector);
	}

	/**
	 * Bind dynamic sequences given this input sequence like {@link #flatMap(Function)}, but preserve
	 * ordering and concatenate emissions instead of merging (no interleave).
	 * Errors will immediately short circuit current concat backlog.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/concatmap.png" alt="">
	 *
	 * @param mapper the function to transform this sequence of O into concatenated sequences of V
	 * @param <V> the produced concatenated type
	 *
	 * @return a new concatenated {@link Stream}
	 */
	public final <V> Stream<V> concatMap(final Function<? super O, Publisher<? extends V>> mapper) {
		return new StreamConcatMap<>(this, mapper, QueueSupplier.<O>xs(), PlatformDependent.XS_BUFFER_SIZE,
				StreamConcatMap.ErrorMode.IMMEDIATE);
	}

	/**
	 * Bind dynamic sequences given this input sequence like {@link #flatMap(Function)}, but preserve
	 * ordering and concatenate emissions instead of merging (no interleave).
	 *
	 * Errors will be delayed after the current concat backlog.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/concatmap.png" alt="">
	 *
	 *
	 * @param mapper the function to transform this sequence of O into concatenated sequences of V
	 * @param <V> the produced concatenated type
	 *
	 * @return a new concatenated {@link Stream}
	 *
	 * @since 2.5
	 */
	public final <V> Stream<V> concatMapDelayError(final Function<? super O, Publisher<? extends V>> mapper) {
		return new StreamConcatMap<>(this, mapper, QueueSupplier.<O>xs(), PlatformDependent.XS_BUFFER_SIZE,
				StreamConcatMap.ErrorMode.END);
	}

	/**
	 * Concatenate emissions of this {@link Stream} with the provided {@link Publisher} (no interleave).
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/concat.png" alt="">
	 *
	 * @param other the {@link Publisher} sequence to concat after this {@link Stream}
	 *
	 * @return a new {@link Stream}
	 */
	@SuppressWarnings("unchecked")
	public final Stream<O> concatWith(final Publisher<? extends O> other) {
		return new StreamConcatArray<>(this, other);
	}

	/**
	 * Subscribe a {@link Consumer} to this {@link Stream} that will consume all the
	 * sequence.  If {@link Stream#getCapacity()} returns an integer value, the {@link Subscriber} will use it as a
	 * prefetch strategy: first request N, then when 25% of N is left to be received on onNext, request N x 0.75. <p>
	 * For a passive version that observe and forward incoming data see {@link #doOnNext(reactor.fn.Consumer)}
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/consume.png" alt="">
	 *
	 * @param consumer the consumer to invoke on each value
	 *
	 * @return a new {@link InterruptableSubscriber} to dispose the {@link Subscription}
	 */
	public final InterruptableSubscriber<O> consume(final Consumer<? super O> consumer) {
		long c = Math.min(Integer.MAX_VALUE, getCapacity());
		InterruptableSubscriber<O> consumerAction;
		if (c == Integer.MAX_VALUE || c == -1L) {
			consumerAction = new InterruptableSubscriber<>(consumer, null, null);
		}
		else {
			consumerAction = InterruptableSubscriber.bounded((int)c, consumer);
		}
		subscribe(consumerAction);
		return consumerAction;
	}

	/**
	 * Subscribe {@link Consumer} to this {@link Stream} that will consume all the
	 * sequence.  If {@link Stream#getCapacity()} returns an integer value, the {@link Subscriber} will use it as a
	 * prefetch strategy: first request N, then when 25% of N is left to be received on onNext, request N x 0.75. <p>
	 * For a passive version that observe and forward incoming data see
	 * {@link #doOnNext(reactor.fn.Consumer)} and {@link #doOnError(reactor.fn.Consumer)}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/consumeerror.png" alt="">
	 *
	 * @param consumer the consumer to invoke on each next signal
	 * @param errorConsumer the consumer to invoke on error signal
	 *
	 * @return a new {@link InterruptableSubscriber} to dispose the {@link Subscription}
	 */
	public final InterruptableSubscriber<O> consume(final Consumer<? super O> consumer, Consumer<? super Throwable> errorConsumer) {
		return consume(consumer, errorConsumer, null);
	}

	/**
	 * Subscribe {@link Consumer} to this {@link Stream} that will consume all the
	 * sequence.  If {@link Stream#getCapacity()} returns an integer value, the {@link Subscriber} will use it as a
	 * prefetch strategy: first request N, then when 25% of N is left to be received on onNext, request N x 0.75. <p>
	 * For a passive version that observe and forward incoming data see {@link #doOnNext(reactor.fn.Consumer)},
	 * {@link #doOnError(reactor.fn.Consumer)} and {@link #doOnComplete(Runnable)},
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/consumecomplete.png" alt="">
	 *
	 * @param consumer the consumer to invoke on each value
	 * @param errorConsumer the consumer to invoke on error signal
	 * @param completeConsumer the consumer to invoke on complete signal
	 *
	 * @return a new {@link InterruptableSubscriber} to dispose the {@link Subscription}
	 */
	public final InterruptableSubscriber<O> consume(final Consumer<? super O> consumer,
			Consumer<? super Throwable> errorConsumer,
			Runnable completeConsumer) {

		long c = Math.min(Integer.MAX_VALUE, getCapacity());

		InterruptableSubscriber<O> consumerAction;
		if (c == Integer.MAX_VALUE || c == -1L) {
			consumerAction = new InterruptableSubscriber<O>(consumer, errorConsumer, completeConsumer);
		}
		else {
			consumerAction = InterruptableSubscriber.bounded((int) c, consumer, errorConsumer,
					completeConsumer);
		}

		subscribe(consumerAction);
		return consumerAction;
	}

	/**
	 * Subscribe
	 * {@link Consumer} to this {@link Stream} that will wait for interaction via {@link ManualSubscriber#request} to
	 * start consuming the sequence.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/consumelater.png" alt="">
	 *
	 * @param consumer the consumer to invoke on each value
	 *
	 * @return a new {@link ManualSubscriber} to request and dispose the {@link Subscription}
	 */
	public final  ManualSubscriber<O> consumeLater(final Consumer<? super O> consumer) {
		return consumeLater(consumer, null, null);
	}

	/**
	 * Subscribe a
	 * {@link Consumer} to this {@link Stream} that will wait for interaction via {@link ManualSubscriber#request} to
	 * start consuming the sequence.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/consumelatererror.png" alt="">
	 *
	 *
	 * @param consumer the consumer to invoke on each next signal
	 * @param errorConsumer the consumer to invoke on each error signal
	 *
	 * @return a new {@link ManualSubscriber} to request and dispose the {@link Subscription}
	 */
	public final  ManualSubscriber<O> consumeLater(final Consumer<? super O> consumer,
			Consumer<? super Throwable> errorConsumer) {
		return consumeLater(consumer, errorConsumer, null);
	}

	/**
	 * Subscribe
	 * {@link Consumer} to this {@link Stream} that will wait for interaction via {@link ManualSubscriber#request} to
	 * start consuming the sequence.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/consumelatercomplete.png" alt="">
	 *
	 * @param consumer the consumer to invoke on each value
	 * @param errorConsumer the consumer to invoke on each error signal
	 * @param completeConsumer the consumer to invoke on complete signal
	 *
	 * @return a new {@link ManualSubscriber} to request and dispose the {@link Subscription}
	 */
	public final  ManualSubscriber<O> consumeLater(final Consumer<? super O> consumer,
			Consumer<? super Throwable> errorConsumer,
			Runnable completeConsumer) {
		return InterruptableSubscriber.bindLater(this, consumer, errorConsumer, completeConsumer);
	}

	/**
	 *
	 * Subscribe a {@link Consumer} to this {@link Stream} that will wait for the returned {@link Publisher} to emit
	 * a {@link Long} demand. The demand {@link Function} factory will be given a {@link Stream} of requests starting
	 * with {@literal 0} then emitting the N - 1 request. The request sequence can be composed and deferred to
	 * produce a throttling effect.
	 * <ul>
	 * <li>If this {@link Stream} terminates, the request sequence will be terminated too.</li>
	 * <li>If the request {@link Publisher} terminates, the consuming {@link Subscription} will be cancelled.</li>
	 * </ul>
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/consumewhen.png" alt="">
	 *
	 *
	 * @param consumer the consumer to invoke on each value
	 *
	 * @return a new {@link InterruptableSubscriber} to dispose the {@link Subscription}
	 */
	@SuppressWarnings("unchecked")
	public final InterruptableSubscriber<O> consumeWhen(final Consumer<? super O> consumer,
			final Function<? super Stream<Long>, ? extends Publisher<? extends Long>> requestMapper) {
		InterruptableSubscriber<O> consumerAction =
				InterruptableSubscriber.adaptive(consumer,
						(Function<? super Publisher<Long>, ? extends Publisher<? extends Long>>)requestMapper,
				Broadcaster.<Long>create(getTimer()));

		subscribe(consumerAction);
		return consumerAction;
	}

	/**
	 * Subscribe a {@link Consumer} to this {@link Stream} that will wait for the returned {@link Function} to produce
	 * a {@link Long} demand. The {@link Function} will be given an input request starting
	 * with {@literal 0} then the N - 1 produced request. This can be used to audo-adapt a request volume to some
	 * latency condition.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/consumewhenrequest.png" alt="">
	 *
	 * @param consumer the consumer to invoke on each value
	 *
	 * @return a new {@link InterruptableSubscriber} to dispose the {@link Subscription}
	 */
	public final InterruptableSubscriber<O> consumeWithRequest(final Consumer<? super O> consumer,
			final Function<Long, ? extends Long> requestMapper) {
		return consumeWhen(consumer, new Function<Stream<Long>, Publisher<? extends Long>>() {
			@Override
			public Publisher<? extends Long> apply(Stream<Long> longStream) {
				return longStream.map(requestMapper);
			}
		});
	}

	/**
	 * Counts the number of values in this {@link Stream}.
	 * The count will be emitted when onComplete is observed.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/count.png" alt="">
	 *
	 * @return a new {@link Mono} of {@link Long} count
	 */
	public final Mono<Long> count() {
		return new MonoCount<>(this);
	}

	/**
	 * Introspect this {@link Stream} graph
	 *
	 * @return {@link ReactiveStateUtils} {@literal Graph} representation of the operational flow
	 */
	public ReactiveStateUtils.Graph debug() {
		return ReactiveStateUtils.scan(this);
	}

	/**
	 * Provide a default unique value if this sequence is completed without any data
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/defaultifempty.png" alt="">
	 *
	 * @param defaultV the alternate value if this sequence is empty
	 *
	 * @return a new {@link Stream}
	 */
	public final Stream<O> defaultIfEmpty(final O defaultV) {
		return new StreamDefaultIfEmpty<>(this, defaultV);
	}

	/**
	 * Delays the {@link Stream#subscribe(Subscriber) subscription} to this {@link Stream} source until a period
	 * given a number of seconds elapses.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/delaysubscription.png" alt="">
	 *
	 * @param seconds period to delay {@link #subscribe(Subscriber)} call
	 *
	 * @return a delayed {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> delaySubscription(long seconds) {
		return delaySubscription(seconds, TimeUnit.SECONDS);
	}

	/**
	 * Delays the {@link Stream#subscribe(Subscriber) subscription} to this {@link Stream} source until the given
	 * period elapses.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/delaysubscription.png" alt="">
	 *
	 * @param delay period in given unit before subscribing this {@link Stream}
	 * @param unit unit of time
	 *
	 * @return a delayed {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> delaySubscription(long delay, TimeUnit unit) {
		return delaySubscription(delay, unit, Timer.global());
	}

	/**
	 * Delays the {@link Stream#subscribe(Subscriber) subscription} to this {@link Stream} source until a period
	 * given a number of seconds elapses.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/delaysubscription.png" alt="">
	 *
	 * @param delay period in given unit before subscribing this {@link Stream}
	 * @param unit unit of time
	 * @param timer a {@link Timer} instance
	 *
	 * @return a delayed {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> delaySubscription(long delay, TimeUnit unit, Timer timer) {
		return delaySubscription(Mono.delay(delay, unit, timer));
	}

	/**
	 * Delays the subscription to the main source until another Publisher
	 * signals a value or completes.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/delaysubscriptionp.png" alt="">
	 *
	 * @param subscriptionDelay a
	 * {@link Publisher} to signal by next or complete this {@link Stream#subscribe(Subscriber}
	 * @param <U> the other source type
	 *
	 * @return a delayed {@link Stream}
	 *
	 * @since 2.5
	 */
	public final <U> Stream<O> delaySubscription(Publisher<U> subscriptionDelay) {
		return new StreamDelaySubscription<>(this, subscriptionDelay);
	}

	/**
	 * A "phantom-operator" working only if this
	 * {@link Stream} is a emits onNext, onError or onComplete {@link Signal}. The relative {@link Subscriber}
	 * callback will be invoked, error {@link Signal} will trigger onError and complete {@link Signal} will trigger
	 * onComplete.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/dematerialize.png" alt="">
	 *
	 * @return a dematerialized {@link Stream}
	 */
	@SuppressWarnings("unchecked")
	public final <X> Stream<X> dematerialize() {
		Stream<Signal<X>> thiz = (Stream<Signal<X>>) this;
		return new StreamDematerialize<>(thiz);
	}

	/**
	 * Run onNext, onComplete and onError on a supplied
	 * {@link Consumer} {@link Runnable} scheduler factory like {@link SchedulerGroup}.
	 *
	 * <p>
	 * Typically used for fast publisher, slow consumer(s) scenarios.
	 * It naturally combines with {@link SchedulerGroup#single} and {@link SchedulerGroup#async} which implement
	 * fast async event loops.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/dispatchon.png" alt="">
	 * <p>
	 * {@code stream.dispatchOn(WorkQueueProcessor.create()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param scheduler a checked factory for {@link Consumer} of {@link Runnable}
	 *
	 * @return a {@link Stream} consuming asynchronously
	 */
	public final Stream<O> dispatchOn(final Callable<? extends Consumer<Runnable>> scheduler) {
		return StreamSource.wrap(Flux.dispatchOn(this, scheduler, true,
				PlatformDependent.SMALL_BUFFER_SIZE, QueueSupplier.<O>small()));
	}

	/**
	 * Run onNext, onComplete and onError on a supplied {@link ExecutorService}.
	 *
	 * <p>
	 * Typically used for fast publisher, slow consumer(s) scenarios.
	 * It naturally combines with {@link SchedulerGroup#single} and {@link SchedulerGroup#async} which implement
	 * fast async event loops.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/dispatchon.png" alt="">
	 * <p>
	 * {@code stream.dispatchOn(ForkJoinPool.commonPool()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param executorService an {@link ExecutorService}
	 *
	 * @return a {@link Stream} consuming asynchronously
	 */
	public final Stream<O> dispatchOn(final ExecutorService executorService) {
		return dispatchOn(new ExecutorServiceScheduler(executorService));
	}

	/**
	 * For each {@link Subscriber}, tracks this {@link Stream} values that have been seen and
	 * filters out duplicates.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/distinct.png" alt="">
	 *
	 * @return a new {@link Stream} with unique values
	 */
	@SuppressWarnings("unchecked")
	public final Stream<O> distinct() {
		return new StreamDistinct<>(this, HASHCODE_EXTRACTOR, hashSetSupplier());
	}

	/**
	 * For each {@link Subscriber}, tracks this {@link Stream} values that have been seen and
	 * filters out duplicates given the extracted key.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/distinctk.png" alt="">
	 *
	 * @param keySelector function to compute comparison key for each element
	 *
	 * @return a new {@link Stream} with values having distinct keys
	 */
	public final <V> Stream<O> distinct(final Function<? super O, ? extends V> keySelector) {
		return new StreamDistinct<>(this, keySelector, hashSetSupplier());
	}

	/**
	 * Create a new {@link Stream} that filters out consecutive equals values.
	 *
	 * @return a new {@link Stream} whose values are the last value of each batch
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final Stream<O> distinctUntilChanged() {
		return new StreamDistinctUntilChanged<O, O>(this, HASHCODE_EXTRACTOR);
	}

	/**
	 * Create a new {@link Stream} that filters out consecutive values having equal keys computed by function
	 *
	 * @param keySelector function to compute comparison key for each element
	 *
	 * @return a new {@link Stream} whose values are the last value of each batch
	 *
	 * @since 2.0
	 */
	public final <V> Stream<O> distinctUntilChanged(final Function<? super O, ? extends V> keySelector) {
		return new StreamDistinctUntilChanged<>(this, keySelector);
	}

	/**
	 * Triggered after the {@link Stream} terminates, either by completing downstream successfully or with an error.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doafterterminate.png" alt="">
	 * <p>
	 * @param afterTerminate the callback to call after {@link Subscriber#onComplete} or {@link Subscriber#onError}
	 *
	 * @return a new unaltered {@link Stream}
	 */
	public final Stream<O> doAfterTerminate(final Runnable afterTerminate) {
		if (this instanceof Fuseable) {
			return new StreamPeekFuseable<>(this, null, null, null, null, afterTerminate, null, null);
		}
		return new StreamPeek<>(this, null, null, null, null, afterTerminate, null, null);
	}

	/**
	 * Triggered when the {@link Stream} is cancelled.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/dooncancel.png" alt="">
	 * <p>
	 * @param onCancel the callback to call on {@link Subscription#cancel}
	 *
	 * @return a new unaltered {@link Stream}
	 */
	public final Stream<O> doOnCancel(final Runnable onCancel) {
		if (this instanceof Fuseable) {
			return new StreamPeekFuseable<>(this, null, null, null, null, null, null, onCancel);
		}
		return new StreamPeek<>(this, null, null, null, null, null, null, onCancel);
	}

	/**
	 * Triggered when the {@link Stream} completes successfully.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/dooncomplete.png" alt="">
	 * <p>
	 * @param onComplete the callback to call on {@link Subscriber#onComplete}
	 *
	 * @return a new unaltered {@link Stream}
	 */
	public final Stream<O> doOnComplete(final Runnable onComplete) {
		if (this instanceof Fuseable) {
			return new StreamPeekFuseable<>(this, null, null, null, onComplete, null, null, null);
		}
		return new StreamPeek<>(this, null, null, null, onComplete, null, null, null);
	}

	/**
	 * Triggered when the {@link Stream} completes with an error.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonerror.png" alt="">
	 * <p>
	 * @param onError the callback to call on {@link Subscriber#onError}
	 *
	 * @return a new unaltered {@link Stream}
	 */
	public final Stream<O> doOnError(final Consumer<Throwable> onError) {
		if (this instanceof Fuseable) {
			return new StreamPeekFuseable<>(this, null, null, onError, null, null, null, null);
		}
		return new StreamPeek<>(this, null, null, onError, null, null, null, null);
	}

	/**
	 * Assign an error handler to exceptions of the given type. Will not stop error propagation, use doOnError(class,
	 * publisher), retry, ignoreError or recover to actively deal with the error
	 *
	 * @param exceptionType the type of exceptions to handle
	 * @param onError the error handler for each error
	 * @param <E> type of the error to handle
	 *
	 * @return a new unaltered {@link Stream}
	 * @since 2.0, 2.5
	 */
	public final <E extends Throwable> Stream<O> doOnError(final Class<E> exceptionType,
			final Consumer<E> onError) {
		return new StreamWhenError<O, E>(this, exceptionType, onError);
	}

	/**
	 * Triggered when the {@link Stream} emits an item.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonnext.png" alt="">
	 * <p>
	 * @param onNext the callback to call on {@link Subscriber#onNext}
	 *
	 * @return a new unaltered {@link Stream}
	 */
	public final Stream<O> doOnNext(final Consumer<? super O> onNext) {
		if (this instanceof Fuseable) {
			return new StreamPeekFuseable<>(this, null, onNext, null, null, null, null, null);
		}
		return new StreamPeek<>(this, null, onNext, null, null, null, null, null);
	}

	/**
	 * Attach a {@link LongConsumer} to this {@link Stream} that will observe any request to this {@link Stream}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonrequest.png" alt="">
	 *
	 * @param consumer the consumer to invoke on each request
	 *
	 * @return a new unaltered {@link Stream}
	 */
	public final Stream<O> doOnRequest(final LongConsumer consumer) {
		if (this instanceof Fuseable) {
			return new StreamPeekFuseable<>(this, null, null, null, null, null, consumer, null);
		}
		return new StreamPeek<>(this, null, null, null, null, null, consumer, null);
	}

	/**
	 * Triggered when the {@link Stream} is subscribed.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonsubscribe.png" alt="">
	 * <p>
	 * @param onSubscribe the callback to call on {@link Subscriber#onSubscribe}
	 *
	 * @return a new unaltered {@link Stream}
	 */
	public final Stream<O> doOnSubscribe(final Consumer<? super Subscription> onSubscribe) {
		if (this instanceof Fuseable) {
			return new StreamPeekFuseable<>(this, onSubscribe, null, null, null, null, null, null);
		}
		return new StreamPeek<>(this, onSubscribe, null, null, null, null, null, null);
	}

	/**
	 * Triggered when the {@link Stream} terminates, either by completing successfully or with an error.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonterminate.png" alt="">
	 * <p>
	 * @param onTerminate the callback to call on {@link Subscriber#onComplete} or {@link Subscriber#onError}
	 *
	 * @return a new unaltered {@link Stream}
	 */
	public final Stream<O> doOnTerminate(final Runnable onTerminate) {
		if (this instanceof Fuseable) {
			return new StreamPeekFuseable<>(this, null, null, null, onTerminate, null, null, null);
		}
		return new StreamPeek<>(this, null, null, null, onTerminate, null, null, null);
	}

	/**
	 * Assign an error handler that will pass eventual associated values and exceptions of the given type. Will not stop
	 * error propagation, use doOnError(class, publisher), retry, ignoreError or recover to actively deal with the
	 * error.
	 *
	 * @param exceptionType the type of exceptions to handle
	 * @param onError the error handler for each error
	 * @param <E> type of the error to handle
	 *
	 * @return {@link Stream}
	 */
	public final <E extends Throwable> Stream<O> doOnValueError(final Class<E> exceptionType,
			final BiConsumer<Object, ? super E> onError) {
		Objects.requireNonNull(exceptionType, "Error type must be provided");
		Objects.requireNonNull(onError, "Error callback must be provided");
		return doOnError(new Consumer<Throwable>() {
			@Override
			public void accept(Throwable cause) {
				if (exceptionType.isAssignableFrom(cause.getClass())) {
					onError.accept(Exceptions.getFinalValueCause(cause), (E) cause);
				}
			}
		});
	}

	/**
	 * Create a new {@link Stream} that accepts a {@link reactor.fn.tuple.Tuple2} of T1 {@link Long} timemillis and T2
	 * {@link <T>} associated data. The timemillis corresponds to the elapsed time between the subscribe and the first
	 * next signal OR between two next signals.
	 *
	 * @return a new {@link Stream} that emits tuples of time elapsed in milliseconds and matching data
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final Stream<Tuple2<Long, O>> elapsed() {
		return new StreamElapsed(this);
	}

	/**
	 * Create a new {@link Stream} that emits an item at a specified index from a source {@link Stream}
	 *
	 * @param index index of an item
	 *
	 * @return a source item at a specified index
	 */
	public final Mono<O> elementAt(final int index) {
		return new MonoElementAt<O>(this, index);
	}

	/**
	 * Create a new {@link Stream} that emits an item at a specified index from a source {@link Stream} or default value
	 * when index is out of bounds
	 *
	 * @param index index of an item
	 * @param defaultValue supply a default value if not found
	 *
	 * @return a source item at a specified index or a default value
	 */
	public final Mono<O> elementAtOrDefault(final int index, final Supplier<? extends O> defaultValue) {
		return new MonoElementAt<>(this, index, defaultValue);
	}

	/**
	 * Create a new {@link Stream} whose values will be only the last value of each batch. Requires a {@code
	 * getCapacity()}
	 *
	 * @param batchSize the batch size to use
	 *
	 * @return a new {@link Stream} whose values are the last value of each batch
	 */
	public final Stream<O> every(final int batchSize) {
		return new StreamDebounce<O>(this, batchSize);
	}

	/**
	 * Create a new {@link Stream} whose values will be only the last value of each batch.
	 *
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit the time unit
	 *
	 * @return a new {@link Stream} whose values are the last value of each batch
	 */
	public final Stream<O> every(long timespan, TimeUnit unit) {
		return every(Integer.MAX_VALUE, timespan, unit, getTimer());
	}

	/**
	 * Create a new {@link Stream} whose values will be only the last value of each batch.
	 *
	 * @param maxSize the max counted size
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit the time unit
	 *
	 * @return a new {@link Stream} whose values are the last value of each batch
	 */
	public final Stream<O> every(int maxSize, long timespan, TimeUnit unit) {
		return every(maxSize, timespan, unit, getTimer());
	}

	/**
	 * Create a new {@link Stream} whose values will be only the last value of each batch.
	 *
	 * @param maxSize the max counted size
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit the time unit
	 * @param timer the Timer to run on
	 *
	 * @return a new {@link Stream} whose values are the last value of each batch
	 */
	public final Stream<O> every(final int maxSize, final long timespan, final TimeUnit unit, final Timer timer) {
		return new StreamDebounce<O>(this, false, maxSize, timespan, unit, timer);
	}

	/**
	 * Create a new {@link Stream} whose values will be only the first value of each batch. <p> When a new batch is
	 * triggered, the first value of that next batch will be pushed into this {@link Stream}.
	 *
	 * @param batchSize the batch size to use
	 *
	 * @return a new {@link Stream} whose values are the first value of each batch)
	 */
	public final Stream<O> everyFirst(final int batchSize) {
		return new StreamDebounce<O>(this, batchSize, true);
	}

	/**
	 * Create a new {@link Stream} that emits <code>true</code> when any value satisfies a predicate and
	 * <code>false</code> otherwise
	 *
	 * @param predicate predicate tested upon values
	 *
	 * @return a new {@link Stream} with <code>true</code> if any value satisfies a predicate and <code>false</code>
	 * otherwise
	 *
	 * @since 2.0, 2.5
	 */
	public final Mono<Boolean> exists(final Predicate<? super O> predicate) {
		return new MonoAny<>(this, predicate);
	}

	/**
	 * Evaluate each accepted value against the given {@link Predicate}. If the predicate test succeeds, the value is
	 * passed into the new {@link Stream}. If the predicate test fails, the value is ignored.
	 *
	 * @param p the {@link Predicate} to test values against
	 *
	 * @return a new {@link Stream} containing only values that pass the predicate test
	 */
	public final Stream<O> filter(final Predicate<? super O> p) {
		if (this instanceof Fuseable) {
			return new StreamFilterFuseable<>(this, p);
		}
		return new StreamFilter<>(this, p);
	}

	/**
	 * Transform the items emitted by this {@link Stream} into Publishers, then flatten the emissions from those by
	 * merging them into a single {@link Stream}, so that they may interleave.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/flatmap.png" alt="">
	 * <p>
	 * @param mapper the {@link Function} to transform input sequence into N sequences {@link Publisher}
	 * @param <V> the merged output sequence type
	 *
	 * @return a new {@link Stream}
	 */
	public final <V> Stream<V> flatMap(final Function<? super O, ? extends Publisher<? extends V>> mapper) {
		return StreamSource.wrap(Flux.flatMap(this,
				mapper,
				PlatformDependent.SMALL_BUFFER_SIZE,
				PlatformDependent.XS_BUFFER_SIZE,
				false));
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into a {@link Stream<O,V>} and pass
	 * it into another {@link Stream}.
	 *
	 * @param fn the transformation function
	 * @param <V> the type of the return value of the transformation function
	 *
	 * @return a new {@link Stream} containing the transformed values
	 *
	 * @since 2.5
	 */
	public final <V> Stream<V> flatMap(final Function<? super O, ? extends Publisher<? extends V>> fn, int
			concurrency) {
		return flatMap(fn, concurrency, PlatformDependent.XS_BUFFER_SIZE);
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into a {@link Stream<O,V>} and pass
	 * it into another {@link Stream}.
	 *
	 * @param fn the transformation function
	 * @param concurrency
	 * @param prefetch
	 * @param <V> the type of the return value of the transformation function
	 *
	 * @return a new {@link Stream} containing the transformed values
	 *
	 * @since 2.5
	 */
	public final <V> Stream<V> flatMap(final Function<? super O, ? extends Publisher<? extends V>> fn, int
			concurrency, int prefetch) {
		return StreamSource.wrap(Flux.flatMap(this,
				fn,
				concurrency,
				prefetch,
				false));
	}

	/**
	 * Transform the signals emitted by this {@link Stream} into Publishers, then flatten the emissions from those by
	 * merging them into a single {@link Stream}, so that they may interleave.
	 * OnError will be transformed into completion signal after its mapping callback has been applied.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/flatmaps.png" alt="">
	 * <p>
	 * @param mapperOnNext the {@link Function} to call on next data and returning a sequence to merge
	 * @param mapperOnError the {@link Function} to call on error signal and returning a sequence to merge
	 * @param mapperOnComplete the {@link Function} to call on complete signal and returning a sequence to merge
	 * @param <R> the output {@link Publisher} type target
	 *
	 * @return a new {@link Stream}
	 */
	@SuppressWarnings("unchecked")
	public final <R> Stream<R> flatMap(Function<? super O, ? extends Publisher<? extends R>> mapperOnNext,
			Function<Throwable, ? extends Publisher<? extends R>> mapperOnError,
			Supplier<? extends Publisher<? extends R>> mapperOnComplete) {
		return StreamSource.wrap(Flux.flatMap(
				Flux.mapSignal(this, mapperOnNext, mapperOnError, mapperOnComplete),
				IDENTITY_FUNCTION, PlatformDependent.SMALL_BUFFER_SIZE, 32, false)
		);
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into n {@code Mono<T1>} and pass
	 * the result as a combined {@code Tuple}.
	 *
	 * @param fn1 the transformation function
	 * @param fn2 the transformation function
	 * @param <T1> the type of the return value of the transformation function
	 * @param <T2> the type of the return value of the transformation function
	 *
	 * @return a new {@link Stream} containing the combined values
	 *
	 * @since 2.5
	 */
	public final <T1, T2> Stream<Tuple2<T1, T2>> forkJoin(
			final Function<? super O, ? extends Mono<? extends T1>> fn1,
			final Function<? super O, ? extends Mono<? extends T2>> fn2) {
		return flatMap(new Function<O, Publisher<? extends Tuple2<T1, T2>>>() {
			@Override
			public Publisher<? extends Tuple2<T1, T2>> apply(O o) {
				return zip(fn1.apply(o), fn2.apply(o));
			}
		});
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into n {@code Mono<T1>} and pass
	 * the result as a combined {@code Tuple}.
	 *
	 * @param fn1 the transformation function
	 * @param fn2 the transformation function
	 * @param fn3 the transformation function
	 * @param <T1> the type of the return value of the transformation function
	 * @param <T2> the type of the return value of the transformation function
	 * @param <T3> the type of the return value of the transformation function
	 *
	 * @return a new {@link Stream} containing the combined values
	 *
	 * @since 2.5
	 */
	public final <T1, T2, T3> Stream<Tuple3<T1, T2, T3>> forkJoin(
			final Function<? super O, ? extends Mono<? extends T1>> fn1,
			final Function<? super O, ? extends Mono<? extends T2>> fn2,
			final Function<? super O, ? extends Mono<? extends T3>> fn3) {
		return flatMap(new Function<O, Publisher<? extends Tuple3<T1, T2, T3>>>() {
			@Override
			public Publisher<? extends Tuple3<T1, T2, T3>> apply(O o) {
				return zip(fn1.apply(o), fn2.apply(o), fn3.apply(o));
			}
		});
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into n {@code Mono<T1>} and pass
	 * the result as a combined {@code Tuple}.
	 *
	 * @param fn1 the transformation function
	 * @param fn2 the transformation function
	 * @param fn3 the transformation function
	 * @param fn4 the transformation function
	 * @param <T1> the type of the return value of the transformation function
	 * @param <T2> the type of the return value of the transformation function
	 * @param <T3> the type of the return value of the transformation function
	 * @param <T4> the type of the return value of the transformation function
	 *
	 * @return a new {@link Stream} containing the combined values
	 *
	 * @since 2.5
	 */
	public final <T1, T2, T3, T4> Stream<Tuple4<T1, T2, T3, T4>> forkJoin(
			final Function<? super O, ? extends Mono<? extends T1>> fn1,
			final Function<? super O, ? extends Mono<? extends T2>> fn2,
			final Function<? super O, ? extends Mono<? extends T3>> fn3,
			final Function<? super O, ? extends Mono<? extends T4>> fn4) {
		return flatMap(new Function<O, Publisher<? extends Tuple4<T1, T2, T3, T4>>>() {
			@Override
			public Publisher<? extends Tuple4<T1, T2, T3, T4>> apply(O o) {
				return zip(fn1.apply(o), fn2.apply(o), fn3.apply(o), fn4.apply(o));
			}
		});
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into n {@code Mono<T1>} and pass
	 * the result as a combined {@code Tuple}.
	 *
	 * @param fn1 the transformation function
	 * @param fn2 the transformation function
	 * @param fn3 the transformation function
	 * @param fn4 the transformation function
	 * @param fn5 the transformation function
	 * @param <T1> the type of the return value of the transformation function
	 * @param <T2> the type of the return value of the transformation function
	 * @param <T3> the type of the return value of the transformation function
	 * @param <T4> the type of the return value of the transformation function
	 * @param <T5> the type of the return value of the transformation function
	 *
	 * @return a new {@link Stream} containing the combined values
	 *
	 * @since 2.5
	 */
	public final <T1, T2, T3, T4, T5> Stream<Tuple5<T1, T2, T3, T4, T5>> forkJoin(
			final Function<? super O, ? extends Mono<? extends T1>> fn1,
			final Function<? super O, ? extends Mono<? extends T2>> fn2,
			final Function<? super O, ? extends Mono<? extends T3>> fn3,
			final Function<? super O, ? extends Mono<? extends T4>> fn4,
			final Function<? super O, ? extends Mono<? extends T5>> fn5) {
		return flatMap(new Function<O, Publisher<? extends Tuple5<T1, T2, T3, T4, T5>>>() {
			@Override
			public Publisher<? extends Tuple5<T1, T2, T3, T4, T5>> apply(O o) {
				return zip(fn1.apply(o), fn2.apply(o), fn3.apply(o), fn4.apply(o), fn5.apply(o));
			}
		});
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into n {@code Mono<T1>} and pass
	 * the result as a combined {@code Tuple}.
	 *
	 * @param fn1 the transformation function
	 * @param fn2 the transformation function
	 * @param fn3 the transformation function
	 * @param fn4 the transformation function
	 * @param fn5 the transformation function
	 * @param fn6 the transformation function
	 * @param <T1> the type of the return value of the transformation function
	 * @param <T2> the type of the return value of the transformation function
	 * @param <T3> the type of the return value of the transformation function
	 * @param <T4> the type of the return value of the transformation function
	 * @param <T5> the type of the return value of the transformation function
	 * @param <T6> the type of the return value of the transformation function
	 *
	 * @return a new {@link Stream} containing the combined values
	 *
	 * @since 2.5
	 */
	public final <T1, T2, T3, T4, T5, T6> Stream<Tuple6<T1, T2, T3, T4, T5, T6>> forkJoin(
			final Function<? super O, ? extends Mono<? extends T1>> fn1,
			final Function<? super O, ? extends Mono<? extends T2>> fn2,
			final Function<? super O, ? extends Mono<? extends T3>> fn3,
			final Function<? super O, ? extends Mono<? extends T4>> fn4,
			final Function<? super O, ? extends Mono<? extends T5>> fn5,
			final Function<? super O, ? extends Mono<? extends T6>> fn6) {
		return flatMap(new Function<O, Publisher<? extends Tuple6<T1, T2, T3, T4, T5, T6>>>() {
			@Override
			public Publisher<? extends Tuple6<T1, T2, T3, T4, T5, T6>> apply(O o) {
				return zip(fn1.apply(o), fn2.apply(o), fn3.apply(o), fn4.apply(o), fn5.apply(o), fn6.apply(o));
			}
		});
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into n {@code Mono<T1>} and pass
	 * the result as a combined {@code Tuple}.
	 *
	 * @param fn1 the transformation function
	 * @param fn2 the transformation function
	 * @param fn3 the transformation function
	 * @param fn4 the transformation function
	 * @param fn5 the transformation function
	 * @param fn6 the transformation function
	 * @param fn7 the transformation function
	 * @param <T1> the type of the return value of the transformation function
	 * @param <T2> the type of the return value of the transformation function
	 * @param <T3> the type of the return value of the transformation function
	 * @param <T4> the type of the return value of the transformation function
	 * @param <T5> the type of the return value of the transformation function
	 * @param <T6> the type of the return value of the transformation function
	 * @param <T7> the type of the return value of the transformation function
	 *
	 * @return a new {@link Stream} containing the combined values
	 *
	 * @since 2.5
	 */
	public final <T1, T2, T3, T4, T5, T6, T7> Stream<Tuple7<T1, T2, T3, T4, T5, T6, T7>> forkJoin(
			final Function<? super O, ? extends Mono<? extends T1>> fn1,
			final Function<? super O, ? extends Mono<? extends T2>> fn2,
			final Function<? super O, ? extends Mono<? extends T3>> fn3,
			final Function<? super O, ? extends Mono<? extends T4>> fn4,
			final Function<? super O, ? extends Mono<? extends T5>> fn5,
			final Function<? super O, ? extends Mono<? extends T6>> fn6,
			final Function<? super O, ? extends Mono<? extends T7>> fn7) {
		return flatMap(new Function<O, Publisher<? extends Tuple7<T1, T2, T3, T4, T5, T6, T7>>>() {
			@Override
			public Publisher<? extends Tuple7<T1, T2, T3, T4, T5, T6, T7>> apply(O o) {
				return zip(fn1.apply(o), fn2.apply(o), fn3.apply(o), fn4.apply(o), fn5.apply(o), fn6.apply(o), fn7.apply(o));
			}
		});
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into n {@code Mono<T1>} and pass
	 * the result as a combined {@code Tuple}.
	 *
	 * @param fn1 the transformation function
	 * @param fn2 the transformation function
	 * @param fn3 the transformation function
	 * @param fn4 the transformation function
	 * @param fn5 the transformation function
	 * @param fn6 the transformation function
	 * @param fn7 the transformation function
	 * @param fn8 the transformation function
	 * @param <T1> the type of the return value of the transformation function
	 * @param <T2> the type of the return value of the transformation function
	 * @param <T3> the type of the return value of the transformation function
	 * @param <T4> the type of the return value of the transformation function
	 * @param <T5> the type of the return value of the transformation function
	 * @param <T6> the type of the return value of the transformation function
	 * @param <T7> the type of the return value of the transformation function
	 * @param <T8> the type of the return value of the transformation function
	 *
	 * @return a new {@link Stream} containing the combined values
	 *
	 * @since 2.5
	 */
	public final <T1, T2, T3, T4, T5, T6, T7, T8> Stream<Tuple8<T1, T2, T3, T4, T5, T6, T7, T8>> forkJoin(
			final Function<? super O, ? extends Mono<? extends T1>> fn1,
			final Function<? super O, ? extends Mono<? extends T2>> fn2,
			final Function<? super O, ? extends Mono<? extends T3>> fn3,
			final Function<? super O, ? extends Mono<? extends T4>> fn4,
			final Function<? super O, ? extends Mono<? extends T5>> fn5,
			final Function<? super O, ? extends Mono<? extends T6>> fn6,
			final Function<? super O, ? extends Mono<? extends T7>> fn7,
			final Function<? super O, ? extends Mono<? extends T8>> fn8) {
		return flatMap(new Function<O, Publisher<? extends Tuple8<T1, T2, T3, T4, T5, T6, T7, T8>>>() {
			@Override
			public Publisher<? extends Tuple8<T1, T2, T3, T4, T5, T6, T7, T8>> apply(O o) {
				return zip(fn1.apply(o), fn2.apply(o), fn3.apply(o), fn4.apply(o), fn5.apply(o), fn6.apply(o), fn7.apply(o), fn8.apply(o));
			}
		});
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into a {@link Stream<O,V>} and pass
	 * it into another {@link Stream}.
	 *
	 * @param fn the transformation function
	 * @param <V> the type of the return value of the transformation function
	 *
	 * @return a new {@link Stream} containing the transformed values
	 *
	 * @since 2.5
	 */
	public final <V> Stream<V> forkJoin(final int concurrency,
			final Function<GroupedStream<Integer, O>, Publisher<V>> fn) {
		Assert.isTrue(concurrency > 0, "Must subscribe once at least, concurrency set to " + concurrency);

		Publisher<V> pub;
		final List<Publisher<? extends V>> publisherList = new ArrayList<>(concurrency);

		for (int i = 0; i < concurrency; i++) {
			final int index = i;
			pub = fn.apply(new GroupedStream<Integer, O>() {

				@Override
				public Integer key() {
					return index;
				}

				@Override
				public long getCapacity() {
					return Stream.this.getCapacity();
				}

				@Override
				public Timer getTimer() {
					return Stream.this.getTimer();
				}

				@Override
				public void subscribe(Subscriber<? super O> s) {
					Stream.this.subscribe(s);
				}
			});

			if (concurrency == 1) {
				return from(pub);
			}
			else {
				publisherList.add(pub);
			}
		}

		return StreamSource.wrap(Flux.merge(publisherList));
	}

	@Override
	public long getCapacity() {
		return -1L;
	}

	@Override
	public int getMode() {
		return FACTORY;
	}

	@Override
	public String getName() {
		return getClass().getSimpleName()
		                 .replace(Stream.class.getSimpleName(), "");
	}

	@Override
	public long getPending() {
		return -1;
	}

	/**
	 * Get the current timer available if any or try returning the shared Environment one (which may cause an error if
	 * no Environment has been globally initialized)
	 *
	 * @return any available timer
	 */
	public Timer getTimer() {
		return Timer.globalOrNull();
	}


	/**
	 * Re-route incoming values into a dynamically created {@link Stream} for each unique key evaluated by the {param
	 * keyMapper}.
	 *
	 * @param keyMapper the key mapping function that evaluates an incoming data and returns a key.
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final <K> Stream<GroupedStream<K, O>> groupBy(final Function<? super O, ? extends K> keyMapper) {
		return groupBy(keyMapper, (Function<O, O>)IDENTITY_FUNCTION);
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Stream} for each unique key evaluated by the {param
	 * keyMapper}.
	 *
	 * @param keyMapper the key mapping function that evaluates an incoming data and returns a key.
	 * @param valueMapper the value mapping function that evaluates an incoming data and returns a value.
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 *
	 * @since 2.5
	 */
	public final <K, V> Stream<GroupedStream<K, V>> groupBy(Function<? super O, ? extends K> keyMapper,
			Function<? super O, ? extends V> valueMapper) {
		return new StreamGroupBy<>(this, keyMapper, valueMapper,
				QueueSupplier.<GroupedStream<K, V>>small(),
				SpscLinkedArrayQueue.<V>unboundedSupplier(PlatformDependent.XS_BUFFER_SIZE),
				PlatformDependent.SMALL_BUFFER_SIZE);
	}

	/**
	 * @return
	 */
	public final Mono<Boolean> hasElements() {
		return new MonoHasElements<>(this);
	}

	/**
	 * @return
	 */
	public final Stream<O> hide() {
		return new StreamHide<>(this);
	}

	/**
	 * @return {@link Stream}
	 *
	 * @see Mono#ignoreElements)
	 */
	public final Mono<O> ignoreElements() {
		return Mono.ignoreElements(this);
	}

	/**
	 * Pass all the nested {@link Publisher} values to a new {@link Stream} until one of them complete. The result will
	 * be produced with a list of each upstream most recent emitted data.
	 *
	 * @return the zipped and joined stream
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final <T> Stream<List<T>> joinWith(Publisher<T> publisher) {
		return zipWith(publisher, (BiFunction<Object, Object, List<T>>) JOIN_BIFUNCTION);
	}

	/**
	 * Create a new {@link Stream} that will signal the last element observed before complete signal.
	 *
	 * @return a new limited {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Mono<O> last() {
		return MonoSource.wrap(new StreamTakeLast<>(this, 1));
	}

	/**
	 * Create a {@link Stream} intercepting all source signals with the returned Subscriber that might choose to pass them
	 * alone to the provided Subscriber (given to the returned {@code subscribe(Subscriber)}.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/lift.png" alt="">
	 * <p>
	 * @param lifter the function accepting the target {@link Subscriber} and returning the {@link Subscriber}
	 * exposed this sequence
	 * @param <V> the output operator type
	 *
	 * @return a new {@link Stream}
	 * @since 2.5
	 */
	public <V> Stream<V> lift(final Function<Subscriber<? super V>, Subscriber<? super O>> lifter) {
		return new StreamSource.Operator<>(this, lifter);
	}

	/**
	 * Observe all Reactive Streams signals and use {@link Logger} support to handle trace implementation. Default will
	 * use {@link Level#INFO} and java.util.logging. If SLF4J is available, it will be used instead.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/log.png" alt="">
	 * <p>
	 * The default log category will be "reactor.core.publisher.FluxLog".
	 *
	 * @return a new unaltered {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> log() {
		return log(null, Logger.ALL);
	}

	/**
	 * Observe all Reactive Streams signals and use {@link Logger} support to handle trace implementation. Default will
	 * use {@link Level#INFO} and java.util.logging. If SLF4J is available, it will be used instead.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/log.png" alt="">
	 * <p>
	 * @param category to be mapped into logger configuration (e.g. org.springframework.reactor).
	 *
	 * @return a new unaltered {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> log(String category) {
		return log(category, Logger.ALL);
	}

	/**
	 * Attach a {@link reactor.core.util.Logger} to this {@link Stream} that will observe any signal emitted.
	 *
	 * @param category The logger name
	 * @param options the bitwise checked flags for observed signals
	 *
	 * @return {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> log(final String category, int options) {
		return log(category, Level.INFO, options);
	}

	/**
	 * Observe Reactive Streams signals matching the passed flags {@code options} and use {@link Logger} support to
	 * handle trace
	 * implementation. Default will
	 * use the passed {@link Level} and java.util.logging. If SLF4J is available, it will be used instead.
	 *
	 * Options allow fine grained filtering of the traced signal, for instance to only capture onNext and onError:
	 * <pre>
	 *     stream.log("category", Level.INFO, Logger.ON_NEXT | LOGGER.ON_ERROR)
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/log.png" alt="">
	 * <p>
	 * @param category to be mapped into logger configuration (e.g. org.springframework.reactor).
	 * @param level the level to enforce for this tracing Flux
	 * @param options a flag option that can be mapped with {@link Logger#ON_NEXT} etc.
	 *
	 * @return a new unaltered {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> log(final String category, Level level, int options) {
		return StreamSource.wrap(Flux.log(this, category, level, options));
	}

	/**
	 * Transform the items emitted by this {@link Stream} by applying a function to each item.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/map.png" alt="">
	 * <p>
	 * @param mapper the transforming {@link Function}
	 * @param <V> the transformed type
	 *
	 * @return a new {@link Stream}
	 */
	public final <V> Stream<V> map(final Function<? super O, ? extends V> mapper) {
		if (this instanceof Fuseable) {
			return new StreamMapFuseable<>(this, mapper);
		}
		return new StreamMap<>(this, mapper);
	}

	/**
	 * Transform the incoming onSubscribe, onNext, onError and onComplete signals into {@link reactor.rx
	 * .Signal}. Since the error is materialized as a {@code Signal}, the propagation will be stopped. Complete signal
	 * will first emit a {@code Signal.complete()} and then effectively complete the stream.
	 *
	 * @return {@link Stream}
	 */
	public final Stream<Signal<O>> materialize() {
		return new StreamMaterialize<>(this);
	}

	/**
	 * Pass all the nested {@link Publisher} values to a new {@link Stream}. Dynamic merge requires use of reactive-pull
	 * offered by default StreamSubscription. If merge hasn't getCapacity() to take new elements because its {@link
	 * #getCapacity()(long)} instructed so, the subscription will buffer them.
	 *
	 * @param <V> the inner stream flowing data type that will be the produced signal.
	 *
	 * @return the merged stream
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final <V> Stream<V> merge() {
		final Stream<? extends Publisher<? extends V>> thiz = (Stream<? extends Publisher<? extends V>>) this;
		return StreamSource.wrap(Flux.merge(thiz));
	}

	/**
	 * Merge emissions of this {@link Stream} with the provided {@link Publisher}, so that they may interleave.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/merge.png" alt="">
	 * <p>
	 * @param other the {@link Publisher} to merge with
	 *
	 * @return a new {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> mergeWith(final Publisher<? extends O> other) {
		return StreamSource.wrap(Flux.merge(this, other));
	}


	/**
	 *
	 *
	 * @return a new {@literal stream} whose values are broadcasted to all subscribers
	 */
	public final ConnectableStream<O> multicast() {
		return publish();
	}

	/**
	 *
	 * @param processor
	 * @return
	 */
	public final ConnectableStream<O> multicast(final Processor<? super O, ? extends O> processor) {
		return multicast(new Supplier<Processor<? super O, ? extends O>>() {
			@Override
			public Processor<? super O, ? extends O> get() {
				return processor;
			}
		});
	}

	/**
	 *
	 * @param processorSupplier
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public final ConnectableStream<O> multicast(
			Supplier<? extends Processor<? super O, ? extends O>> processorSupplier) {
		return multicast(processorSupplier, IDENTITY_FUNCTION);
	}

	/**
	 *
	 * @param processor
	 * @param selector
	 * @param <U>
	 * @return
	 */
	public final <U> ConnectableStream<U> multicast(final Processor<? super O, ? extends O>
			processor, Function<Stream<O>, ? extends Publisher<? extends U>> selector) {
		return multicast(new Supplier<Processor<? super O, ? extends O>>() {
			@Override
			public Processor<? super O, ? extends O> get() {
				return processor;
			}
		}, selector);
	}

	/**
	 *
	 * @param processorSupplier
	 * @param selector
	 * @param <U>
	 * @return
	 */
	public final <U> ConnectableStream<U> multicast(Supplier<? extends Processor<? super O, ? extends O>>
			processorSupplier, Function<Stream<O>, ? extends Publisher<? extends U>> selector) {
		return new StreamMulticast<>(this, processorSupplier, selector);
	}

	/**
	 * Create a new {@link Stream} whose only value will be the current instance of the {@link Stream}.
	 *
	 * @return a new {@link Stream} whose only value will be the materialized current {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<Stream<O>> nest() {
		return just(this);
	}

	/**
	 * Emit only the first item emitted by this {@link Stream}.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/next.png" alt="">
	 * <p>
	 * If the sequence emits more than 1 data, emit {@link ArrayIndexOutOfBoundsException}.
	 *
	 * @return a new {@link Mono}
	 *
	 * @since 2.0, 2.5
	 */
	public final Mono<O> next() {
		return Mono.from(this);
	}

	/**
	 * Attach a No-Op {@link Stream} that only serves the purpose of blocking incoming values if not enough demand is signaled
	 * downstream. A blocking capable stream will prevent underlying dispatcher to be saturated and behave in an
	 * uncontrolled fashion while focusing on low latency with an eager demand upstream.
	 *
	 * @return a blocking stream
	 *
	 * @since 2.5
	 */
	public final Stream<O> onBackpressureBlock() {
		return onBackpressureBlock(WaitStrategy.blocking());
	}

	/**
	 * Attach a No-Op {@link Stream} that only serves the purpose of blocking incoming values if not enough demand is signaled
	 * downstream. A blocking capable stream will prevent underlying dispatcher to be saturated and behave in an
	 * uncontrolled fashion while focusing on low latency with an eager demand upstream.
	 *
	 * @return a blocking stream
	 *
	 * @since 2.5
	 */
	public final Stream<O> onBackpressureBlock(WaitStrategy waitStrategy) {
		return new StreamBlock<>(this, waitStrategy);
	}

	/**
	 * Attach a No-Op {@link Stream} that only serves the purpose of buffering incoming values if not enough demand is signaled
	 * downstream. A buffering capable stream will prevent underlying dispatcher to be saturated (and sometimes
	 * blocking).
	 *
	 * @return a buffered stream
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> onBackpressureBuffer() {
		return onBackpressureBuffer(PlatformDependent.SMALL_BUFFER_SIZE);
	}

	/**
	 * Attach a No-Op {@link Stream} that only serves the purpose of buffering incoming values if not enough demand is signaled
	 * downstream. A buffering capable stream will prevent underlying dispatcher to be saturated (and sometimes
	 * blocking).
	 *
	 * @param size max buffer size
	 *
	 * @return a buffered stream
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> onBackpressureBuffer(final int size) {
		return new StreamSource<O, O>(this) {
			@Override
			public String getName() {
				return "onBackpressureBuffer";
			}

			@Override
			public void subscribe(Subscriber<? super O> s) {
				Processor<O, O> emitter = EmitterProcessor.replay(size);
				emitter.subscribe(s);
				source.subscribe(emitter);
			}
		};
	}

	/**
	 * Attach a No-Op {@link Stream} that only serves the purpose of dropping incoming values if not enough demand is signaled
	 * downstream. A dropping stream will prevent underlying dispatcher to be saturated (and sometimes blocking).
	 *
	 * @return a dropping {@link Stream}
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> onBackpressureDrop() {
		return new StreamDrop<>(this);
	}

	/**
	 * Attach a No-Op {@link Stream} that only serves the purpose of dropping incoming values if not enough demand is signaled
	 * downstream. A dropping stream will prevent underlying dispatcher to be saturated (and sometimes blocking).
	 *
	 * @return a dropping {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> onBackpressureDrop(Consumer<? super O> onDropped) {
		return new StreamDrop<>(this, onDropped);
	}

	/**
	 * Attach a No-Op {@link Stream} that only serves the purpose of buffering incoming values if not enough demand is signaled
	 * downstream. A buffering capable stream will prevent underlying dispatcher to be saturated (and sometimes
	 * blocking).
	 *
	 * @return a buffered {@link Stream}
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> onBackpressureError() {
		return onBackpressureDrop(new Consumer<O>() {
			@Override
			public void accept(O o) {
				Exceptions.failWithOverflow();
			}
		});
	}

	/**
	 * @return
	 *
	 * @since 2.5
	 */
	public final Stream<O> onBackpressureLatest() {
		return new StreamLatest<>(this);
	}

	/**
	 * Subscribe to a returned fallback publisher when any error occurs.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onerrorresumewith.png" alt="">
	 * <p>
	 * @param fallback the {@link Function} mapping the error to a new {@link Publisher} sequence
	 *
	 * @return a new {@link Stream}
	 */
	public final Stream<O> onErrorResumeWith(final Function<Throwable, ? extends Publisher<? extends O>> fallback) {
		return StreamSource.wrap(Flux.onErrorResumeWith(this, fallback));
	}

	/**
	 * Produce a default value if any error occurs.
	 *
	 * @param fallback the error handler for each error
	 *
	 * @return {@link Stream}
	 */
	public final Stream<O> onErrorReturn(final O fallback) {
		return switchOnError(just(fallback));
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Stream} for each unique key evaluated by the {param
	 * keyMapper}. The hashcode of the incoming data will be used for partitioning over {@link
	 * SchedulerGroup#DEFAULT_POOL_SIZE} buckets. That means that at any point of time at most {@link
	 * SchedulerGroup#DEFAULT_POOL_SIZE} number of streams will be created and used accordingly to the current hashcode % n
	 * result.
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values routed to this partition
	 *
	 * @since 2.0
	 */
	public final Stream<GroupedStream<Integer, O>> partition() {
		return partition(SchedulerGroup.DEFAULT_POOL_SIZE);
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Stream} for each unique key evaluated by the {param
	 * keyMapper}. The hashcode of the incoming data will be used for partitioning over the buckets number passed. That
	 * means that at any point of time at most {@code buckets} number of streams will be created and used accordingly to
	 * the positive modulo of the current hashcode with respect to the number of buckets specified.
	 *
	 * @param buckets the maximum number of buckets to partition the values across
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values routed to this partition
	 *
	 * @since 2.0
	 */
	public final Stream<GroupedStream<Integer, O>> partition(final int buckets) {
		return groupBy(new Function<O, Integer>() {
			@Override
			public Integer apply(O o) {
				int bucket = o.hashCode() % buckets;
				return bucket < 0 ? bucket + buckets : bucket;
			}
		});
	}

	/**
	 *
	 * @return a subscribed Promise (caching the signal unlike {@link #next)
	 */
	public final Promise<O> promise() {
		return Promise.from(this);
	}

	/**
	 *
	 * @return
	 */
	public final ConnectableStream<O> publish() {
		return publish(PlatformDependent.SMALL_BUFFER_SIZE);
	}

	/**
	 *
	 * @param prefetch
	 * @return
	 */
	public final ConnectableStream<O> publish(int prefetch) {
		return new StreamPublish<>(this, prefetch, QueueSupplier.<O>get(prefetch));
	}
	/**
	 * Run subscribe, onSubscribe and request on a supplied
	 * {@link Consumer} {@link Runnable} factory like {@link SchedulerGroup}.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/publishon.png" alt="">
	 * <p>
	 * <p>
	 * Typically used for slow publisher e.g., blocking IO, fast consumer(s) scenarios.
	 * It naturally combines with {@link SchedulerGroup#io} which implements work-queue thread dispatching.
	 *
	 * <p>
	 * {@code stream.publishOn(WorkQueueProcessor.create()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param schedulerFactory a checked factory for {@link Consumer} of {@link Runnable}
	 *
	 * @return a {@link Stream} publishing asynchronously
	 */
	public final Stream<O> publishOn(final Callable<? extends Consumer<Runnable>> schedulerFactory) {
		return StreamSource.wrap(Flux.publishOn(this, schedulerFactory));
	}

	/**
	 * @see Flux#publishOn
	 *
	 * @return a new dispatched {@link Stream}
	 */
	public final Stream<O> publishOn(final ExecutorService executorService) {
		return publishOn(new ExecutorServiceScheduler(executorService));
	}

	/**
	 * Reduce the values passing through this {@link Stream} into an object {@code T}. This is a simple functional way
	 * for accumulating values. The arguments are the N-1 and N next signal in this order.
	 *
	 * @param fn the reduce function
	 *
	 * @return a new {@link Stream} whose values contain only the reduced objects
	 * @since 1.1, 2.0, 2.5
	 */
	public final Mono<O> reduce(final BiFunction<O, O, O> fn) {
		if(this instanceof Supplier){
			return MonoSource.wrap(this);
		}
		return new MonoAggregate<>(this, fn);
	}

	/**
	 * Reduce the values passing through this {@link Stream} into an object {@code A}. The arguments are the N-1 and N
	 * next signal in this order.
	 *
	 * @param fn the reduce function
	 * @param initial the initial argument to pass to the reduce function
	 * @param <A> the type of the reduced object
	 *
	 * @return a new {@link Stream} whose values contain only the reduced objects
	 * @since 1.1, 2.0, 2.5
	 */
	public final <A> Mono<A> reduce(final A initial, BiFunction<A, ? super O, A> fn) {

		return reduceWith(new Supplier<A>() {
			@Override
			public A get() {
				return initial;
			}
		}, fn);
	}

	/**
	 * Reduce the values passing through this {@link Stream} into an object {@code A}. The arguments are the N-1 and N
	 * next signal in this order.
	 *
	 * @param fn the reduce function
	 * @param initial the initial argument to pass to the reduce function
	 * @param <A> the type of the reduced object
	 *
	 * @return a new {@link Stream} whose values contain only the reduced objects
	 *
	 * @since 1.1, 2.0, 2.5
	 */
	public final <A> Mono<A> reduceWith(final Supplier<A> initial, BiFunction<A, ? super O, A> fn) {
		return new MonoReduce<>(this, initial, fn);
	}

	/**
	 * Create a new {@link Stream} which will keep re-subscribing its oldest parent-child stream pair on complete.
	 *
	 * @return a new infinitely repeated {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> repeat() {
		return repeat(ALWAYS_BOOLEAN_SUPPLIER);
	}

	/**
	 * Create a new {@link Stream} which will keep re-subscribing its oldest parent-child stream pair on complete.
	 *
	 * @param predicate the boolean to evaluate on complete
	 *
	 * @since 2.5
	 */
	public final Stream<O> repeat(BooleanSupplier predicate) {
		return new StreamRepeatPredicate<>(this, predicate);
	}

	/**
	 * Create a new {@link Stream} which will keep re-subscribing its oldest parent-child stream pair on complete. The
	 * action will be propagating complete after {@param numRepeat}.
	 *
	 * @param numRepeat the number of times to re-subscribe on complete
	 *
	 * @return a new repeated {@link Stream}
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> repeat(final long numRepeat) {
		return new StreamRepeat<O>(this, numRepeat);
	}

	/**
	 * Create a new {@link Stream} which will keep re-subscribing its oldest parent-child stream pair on complete. The
	 * action will be propagating complete after {@param numRepeat}.
	 *
	 * @param numRepeat the number of times to re-subscribe on complete
	 * @param predicate the boolean to evaluate on complete
	 *
	 * @return a new repeated {@link Stream}
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> repeat(final long numRepeat, BooleanSupplier predicate) {
		return new StreamRepeatPredicate<>(this, countingBooleanSupplier(predicate, numRepeat));
	}

	/**
	 * Create a new {@link Stream} which will re-subscribe its oldest parent-child stream pair if the backOff stream
	 * produced by the passed mapper emits any next signal. It will propagate the complete and error if the backoff
	 * stream emits the relative signals.
	 *
	 * @param backOffStream the function providing a stream signalling an anonymous object on each complete
	 * a new stream that applies some backoff policy, e.g. @{link Stream#timer(long)}
	 *
	 * @return a new repeated {@link Stream}
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> repeatWhen(final Function<Stream<Long>, ? extends Publisher<?>> backOffStream) {
		return new StreamRepeatWhen<O>(this, backOffStream);
	}

	/**
	 * Request the parent stream every time the passed throttleStream signals a Long request volume. Complete and Error
	 * signals will be propagated.
	 *
	 * @param throttleStream a function that takes a broadcasted stream of request signals and must return a stream of
	 * valid request signal (long).
	 *
	 * @return a new {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> requestWhen(final Function<? super Stream<? extends Long>, ? extends Publisher<? extends
			Long>> throttleStream) {
		return new StreamThrottleRequestWhen<O>(this, getTimer(), throttleStream);
	}

	/**
	 * Create a new {@link Stream} which will re-subscribe its oldest parent-child stream pair. The action will start
	 * propagating errors after {@literal Integer.MAX_VALUE}.
	 *
	 * @return a new fault-tolerant {@link Stream}
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final Stream<O> retry() {
		return retry(ALWAYS_PREDICATE);
	}
	
	/**
	 * Create a new {@link Stream} which will re-subscribe its oldest parent-child stream pair. The action will start
	 * propagating errors after {@param numRetries}. This is generally useful for retry strategies and fault-tolerant
	 * streams.
	 *
	 * @param numRetries the number of times to tolerate an error
	 *
	 * @return a new fault-tolerant {@link Stream}
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> retry(long numRetries) {
		return new StreamRetry<O>(this, numRetries);
	}

	/**
	 * Create a new {@link Stream} which will re-subscribe its oldest parent-child stream pair. {@param retryMatcher}
	 * will test an incoming {@link Throwable}, if positive the retry will occur. This is generally useful for retry
	 * strategies and fault-tolerant streams.
	 *
	 * @param retryMatcher the predicate to evaluate if retry should occur based on a given error signal
	 *
	 * @return a new fault-tolerant {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> retry(Predicate<Throwable> retryMatcher) {
		return new StreamRetryPredicate<>(this, retryMatcher);
	}

	/**
	 * Create a new {@link Stream} which will re-subscribe its oldest parent-child stream pair. The action will start
	 * propagating errors after {@param numRetries}. {@param retryMatcher} will test an incoming {@Throwable}, if
	 * positive the retry will occur (in conjonction with the {@param numRetries} condition). This is generally useful
	 * for retry strategies and fault-tolerant streams.
	 *
	 * @param numRetries the number of times to tolerate an error
	 * @param retryMatcher the predicate to evaluate if retry should occur based on a given error signal
	 *
	 * @return a new fault-tolerant {@link Stream}
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> retry(final long numRetries, final Predicate<Throwable> retryMatcher) {
		return new StreamRetryPredicate<>(this, countingPredicate(retryMatcher, numRetries));
	}

	/**
	 * Create a new {@link Stream} which will re-subscribe its oldest parent-child stream pair if the backOff stream
	 * produced by the passed mapper emits any next data or complete signal. It will propagate the error if the backOff
	 * stream emits an error signal.
	 *
	 * @param backOffStream the function taking the error stream as an downstream and returning a new stream that
	 * applies some backoff policy e.g. Stream.timer
	 *
	 * @return a new fault-tolerant {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> retryWhen(final Function<Stream<Throwable>, ? extends Publisher<?>> backOffStream) {
		return new StreamRetryWhen<O>(this, backOffStream);
	}

	/**
	 * Create a new {@link Stream} whose values will be only the first value signalled after the next {@code other}
	 * emission.
	 *
	 * @param other the sampler stream
	 *
	 * @return a new {@link Stream} whose values are the  value of each batch
	 */
	public final <U> Stream<O> sample(Publisher<U> other) {
		return new StreamSample<>(this, other);
	}

	/**
	 * Create a new {@link Stream} whose values will be only the first value of each batch.
	 *
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit the time unit
	 *
	 * @return a new {@link Stream} whose values are the first value of each batch
	 */
	public final Stream<O> sampleFirst(final long timespan, final TimeUnit unit) {
		return sampleFirst(new Function<O, Publisher<Long>>() {
			@Override
			public Publisher<Long> apply(O o) {
				return Mono.delay(timespan, unit);
			}
		});
	}

	/**
	 * Takes a value from upstream then uses the duration provided by a
	 * generated Publisher to skip other values until that other Publisher signals.
	 *
	 * @param sampler the sampling function returning eventually to stop skipping upstream next
	 * @param <U>
	 *
	 * @return a new {@link Stream} whose values are the first value of each batch
	 */
	public final <U> Stream<O> sampleFirst(Function<? super O, ? extends Publisher<U>> sampler) {
		return new StreamThrottleFirst<>(this, sampler);
	}

	/**
	 * Create a new {@link Stream} whose values will be only the first value of each batch.
	 *
	 * @param maxSize the max counted size
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit the time unit
	 *
	 * @return a new {@link Stream} whose values are the first value of each batch
	 */
	public final Stream<O> sampleFirst(int maxSize, long timespan, TimeUnit unit) {
		return sampleFirst(maxSize, timespan, unit, getTimer());
	}

	/**
	 * Create a new {@link Stream} whose values will be only the first value of each batch.
	 *
	 * @param maxSize the max counted size
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit the time unit
	 * @param timer the Timer to run on
	 *
	 * @return a new {@link Stream} whose values are the first value of each batch
	 */
	public final Stream<O> sampleFirst(final int maxSize, final long timespan, final TimeUnit unit, final Timer timer) {
		return new StreamDebounce<O>(this, true, maxSize, timespan, unit, timer);
	}

	/**
	 * Emits the last value from upstream only if there were no newer values emitted
	 * during the time window provided by a publisher for that particular last value.
	 *
	 * @param throttler the throttling function to Publisher
	 * @param <U> the throttling type
	 *
	 * @return a new {@link Stream} whose values are the first value of each batch
	 */
	@SuppressWarnings("unchecked")
	public final <U> Stream<O> sampleTimeout(Function<? super O, ? extends Publisher<U>> throttler) {
		return new StreamThrottleTimeout<>(this, throttler, QueueSupplier.xs());
	}

	/**
	 * Scan the values passing through this {@link Stream} into an object {@code A}. The arguments are the N-1 and N
	 * next signal in this order.
	 *
	 * @param fn the reduce function
	 *
	 * @return a new {@link Stream} whose values contain only the reduced objects
	 *
	 * @since 1.1, 2.0
	 */
	public final Stream<O> scan(final BiFunction<O, O, O> fn) {
		return new StreamAccumulate<>(this, fn);
	}

	/**
	 * Scan the values passing through this {@link Stream} into an object {@code A}. The given initial object will be
	 * passed to the function's {@link Tuple2} argument. Behave like Reduce but triggers downstream {@link Stream} for every
	 * transformation.
	 *
	 * @param initial the initial argument to pass to the reduce function
	 * @param fn the scan function
	 * @param <A> the type of the reduced object
	 *
	 * @return a new {@link Stream} whose values contain only the reduced objects
	 *
	 * @since 1.1, 2.0
	 */
	public final <A> Stream<A> scan(final A initial, final BiFunction<A, ? super O, A> fn) {
		return new StreamScan<>(this, initial, fn);
	}

	/**
	 * @return
	 */
	public final Mono<O> single() {
		return new MonoSingle<>(this);
	}

	/**
	 * @param defaultSupplier
	 *
	 * @return
	 */
	public final Mono<O> singleOrDefault(Supplier<? extends O> defaultSupplier) {
		return new MonoSingle<>(this, defaultSupplier);
	}

	/**
	 * @return
	 */
	public final Mono<O> singleOrEmpty() {
		return new MonoSingle<>(this, MonoSingle.<O>completeOnEmptySequence());
	}

	/**
	 * Create a new {@link Stream} that will NOT signal next elements up to {@param max} times.
	 *
	 * @param max the number of times to drop next signals before starting
	 *
	 * @return a new limited {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> skip(long max) {
		if (max > 0) {
			return new StreamSkip<>(this, max);
		}
		else {
			return this;
		}
	}

	/**
	 * Create a new {@link Stream} that will NOT signal next elements up to the specified {@param time}.
	 *
	 * @param time the time window to drop next signals before starting
	 * @param unit the time unit to use
	 *
	 * @return a new limited {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> skip(long time, TimeUnit unit) {
		return skip(time, unit, getTimer());
	}

	/**
	 * Create a new {@link Stream} that will NOT signal next elements up to the specified {@param time}.
	 *
	 * @param time the time window to drop next signals before starting
	 * @param unit the time unit to use
	 * @param timer the Timer to use
	 *
	 * @return a new limited {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> skip(final long time, final TimeUnit unit, final Timer timer) {
		if (time > 0) {
			Assert.isTrue(timer != null, "Timer can't be found, try assigning an environment to the stream");
			return skipUntil(Mono.delay(time, unit, timer));
		}
		else {
			return this;
		}
	}

	/**
	 * Create a new {@link Stream} that WILL NOT signal last {@param n} elements
	 *
	 * @param n the number of elements to ignore before completion
	 *
	 * @return a new limited {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> skipLast(int n) {
		return new StreamSkipLast<>(this, n);
	}

	/**
	 * Create a new {@link Stream} that WILL NOT signal next elements until {@param other} emits.
	 * If {@code other} terminates, then terminate the returned stream and cancel this stream.
	 *
	 * @param other the Publisher to signal when to stop skipping
	 *
	 * @return a new limited {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> skipUntil(final Publisher<?> other) {
		return new StreamSkipUntil<>(this, other);
	}

	/**
	 * Create a new {@link Stream} that WILL NOT signal next elements while {@param limitMatcher} is true
	 *
	 * @param limitMatcher the predicate to evaluate for starting dropping events
	 *
	 * @return a new limited {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> skipWhile(final Predicate<? super O> limitMatcher) {
		return new StreamSkipWhile<>(this, limitMatcher);
	}

	/**
	 * Start emitting all items from the passed publisher then emits from the current stream.
	 *
	 * @return the merged stream
	 *
	 * @since 2.0
	 */
	public final Stream<O> startWith(final Iterable<O> iterable) {
		return startWith(fromIterable(iterable));
	}

	/**
	 * Start emitting all items from the passed publisher then emits from the current stream.
	 *
	 * @return the merged stream
	 *
	 * @since 2.0
	 */
	public final Stream<O> startWith(final O value) {
		return startWith(just(value));
	}

	/**
	 * Start emitting all items from the passed publisher then emits from the current stream.
	 *
	 * @return the merged stream
	 *
	 * @since 2.0
	 */
	public final Stream<O> startWith(final Publisher<? extends O> publisher) {
		if (publisher == null) {
			return this;
		}
		return concat(publisher, this);
	}

	/**
	 * Start the chain and request unbounded demand.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/unbounded.png" alt="">
	 * <p>
	 *
	 * @return a {@link InterruptableSubscriber} to dispose and cancel the underlying {@link Subscription}
	 */
	public final InterruptableSubscriber<O> subscribe() {
		InterruptableSubscriber<O> s = new InterruptableSubscriber<>(null, null, null);
		subscribe(s);
		return s;
	}

	/**
	 * Subscribe
	 * {@link Consumer} to this {@link Stream} that will wait for interaction via {@link ManualSubscriber#request} to
	 * start consuming the sequence.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/subscribelater.png" alt="">
	 *
	 * @return a new {@link ManualSubscriber} to request and dispose the {@link Subscription}
	 */
	public ManualSubscriber<O> subscribeLater() {
		return consumeLater(null);
	}

	/**
	 *
	 * A chaining {@link Publisher#subscribe(Subscriber)} alternative to inline composition type conversion to a hot
	 * emitter (e.g. reactor FluxProcessor Broadcaster and Promise or rxjava Subject).
	 *
	 * {@code stream.subscribeWith(WorkQueueProcessor.create()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param subscriber the {@link Subscriber} to subscribe and return
	 * @param <E> the reified type from the input/output subscriber
	 *
	 * @return the passed {@link Subscriber}
	 */
	public final <E extends Subscriber<? super O>> E subscribeWith(E subscriber) {
		subscribe(subscriber);
		return subscriber;
	}

	/**
	 * Provide an alternative if this sequence is completed without any data
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/switchifempty.png" alt="">
	 * <p>
	 * @param alternate the alternate publisher if this sequence is empty
	 *
	 * @return a new {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> switchIfEmpty(final Publisher<? extends O> alternate) {
		return new StreamSwitchIfEmpty<>(this, alternate);
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into a {@link Stream<O,V>} and pass
	 * it into another {@link Stream}. The produced stream will emit the data from the most recent transformed stream.
	 *
	 * @param fn the transformation function
	 * @param <V> the type of the return value of the transformation function
	 *
	 * @return a new {@link Stream} containing the transformed values
	 *
	 * @since 1.1, 2.0
	 */
	@SuppressWarnings("unchecked")
	public final <V> Stream<V> switchMap(final Function<? super O, Publisher<? extends V>> fn) {
		return new StreamSwitchMap<>(this, fn, QueueSupplier.xs(), PlatformDependent.XS_BUFFER_SIZE);
	}

	/**
	 * Subscribe to the given fallback {@link Publisher} if an error is observed on this {@link Stream}
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/switchonerror.png" alt="">
	 * <p>
	 *
	 * @param fallback the alternate {@link Publisher}
	 *
	 * @return a new {@link Stream}
	 */
	public final Stream<O> switchOnError(final Publisher<? extends O> fallback) {
		return StreamSource.wrap(Flux.onErrorResumeWith(this, new Function<Throwable, Publisher<? extends O>>() {
			@Override
			public Publisher<? extends O> apply(Throwable throwable) {
				return fallback;
			}
		}));
	}

	/**
	 * Create a new {@link Stream} that will signal next elements up to {@param max} times.
	 *
	 * @param max the number of times to broadcast next signals before completing
	 *
	 * @return a new limited {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> take(final long max) {
		return new StreamTake<O>(this, max);
	}

	/**
	 * Create a new {@link Stream} that will signal next elements up to the specified {@param time}.
	 *
	 * @param time the time window to broadcast next signals before completing
	 * @param unit the time unit to use
	 *
	 * @return a new limited {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> take(long time, TimeUnit unit) {
		return take(time, unit, getTimer());
	}

	/**
	 * Create a new {@link Stream} that will signal next elements up to the specified {@param time}.
	 *
	 * @param time the time window to broadcast next signals before completing
	 * @param unit the time unit to use
	 * @param timer the Timer to use
	 *
	 * @return a new limited {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> take(final long time, final TimeUnit unit, final Timer timer) {
		if (time > 0) {
			Assert.isTrue(timer != null, "Timer can't be found, try assigning an environment to the stream");
			return takeUntil(Mono.delay(time, unit, timer));
		}
		else {
			return empty();
		}
	}

	/**
	 * Create a new {@link Stream} that will signal the last {@param n} elements.
	 *
	 * @param n the max number of last elements to capture before onComplete
	 *
	 * @return a new limited {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> takeLast(int n) {
		return new StreamTakeLast<>(this, n);
	}

	/**
	 * Create a new {@link Stream} that will signal next elements until {@param limitMatcher} is true.
	 *
	 * @param limitMatcher the predicate to evaluate for starting dropping events and completing
	 *
	 * @return a new limited {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> takeUntil(final Predicate<? super O> limitMatcher) {
		return new StreamTakeUntilPredicate<>(this, limitMatcher);
	}

	/**
	 * Create a new {@link Stream} that will signal next elements until {@param other} emits.
	 * Completion and Error will cause this stream to cancel.
	 *
	 * @param other the {@link Publisher} to signal when to stop replaying signal from upstream
	 *
	 * @return a new limited {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> takeUntil(final Publisher<?> other) {
		return new StreamTakeUntil<>(this, other);
	}

	/**
	 * Create a new {@link Stream} that will signal next elements while {@param limitMatcher} is true.
	 *
	 * @param limitMatcher the predicate to evaluate for starting dropping events and completing
	 *
	 * @return a new limited {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> takeWhile(final Predicate<? super O> limitMatcher) {
		return new StreamTakeWhile<O>(this, limitMatcher);
	}

	/**
	 * Create a {@link StreamTap} that maintains a reference to the last value seen by this {@link Stream}. The {@link StreamTap} is
	 * continually updated when new values pass through the {@link Stream}.
	 *
	 * @return the new {@link StreamTap}
	 *
	 * @see Consumer
	 */
	public final StreamTap<O> tap() {
		return StreamTap.tap(this);
	}

	/**
	 * @see #sampleFirst(Function)
	 *
	 * @return a new {@link Stream}
	 *
	 * @since 2.5
	 */
	public final <U> Stream<O> throttleFirst(Function<? super O, ? extends Publisher<U>> throttler) {
		return sampleFirst(throttler);
	}

	/**
	 * @see #sample(Publisher)
	 *
	 * @return a new {@link Stream}
	 *
	 * @since 2.5
	 */
	public final <U> Stream<O> throttleLast(Publisher<U> throttler) {
		return sample(throttler);
	}

	/**
	 * Request once the parent stream every {@param period} milliseconds. Timeout is run on the environment root timer.
	 *
	 * @param period the period in milliseconds between two notifications on this stream
	 *
	 * @return a new {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> throttleRequest(final long period) {
		final Timer timer = getTimer();
		Assert.state(timer != null, "Cannot use default timer as no environment has been provided to this " + "Stream");

		return new StreamThrottleRequest<O>(this, timer, period);
	}

	/**
	 * @see #sampleTimeout(Function)
	 *
	 * @return a new {@link Stream}
	 *
	 * @since 2.5
	 */
	public final <U> Stream<O> throttleTimeout(Function<? super O, ? extends Publisher<U>> throttler) {
		return sampleTimeout(throttler);
	}

	/**
	 * Signal an error if no data has been emitted for {@param timeout} milliseconds. Timeout is run on the environment
	 * root timer. <p> A Timeout Exception will be signaled if no data or complete signal have been sent within the
	 * given period.
	 *
	 * @param timeout the timeout in milliseconds between two notifications on this composable
	 *
	 * @return a new {@link Stream}
	 *
	 * @since 1.1, 2.0
	 */
	public final Stream<O> timeout(long timeout) {
		return timeout(timeout, null);
	}

	/**
	 * Signal an error if no data has been emitted for {@param timeout} milliseconds. Timeout is run on the environment
	 * root timer. <p> A Timeout Exception will be signaled if no data or complete signal have been sent within the
	 * given period.
	 *
	 * @param timeout the timeout in unit between two notifications on this composable
	 * @param unit the time unit
	 *
	 * @return a new {@link Stream}
	 *
	 * @since 1.1, 2.0
	 */
	public final Stream<O> timeout(long timeout, TimeUnit unit) {
		return timeout(timeout, unit, null);
	}

	/**
	 * Switch to the fallback Publisher if no data has been emitted for {@param timeout} milliseconds. Timeout is run on
	 * the environment root timer. <p> The current subscription will be cancelled and the fallback publisher subscribed.
	 * <p> A Timeout Exception will be signaled if no data or complete signal have been sent within the given period.
	 *
	 * @param timeout the timeout in unit between two notifications on this composable
	 * @param unit the time unit
	 * @param fallback the fallback {@link Publisher} to subscribe to once the timeout has occured
	 *
	 * @return a new {@link Stream}
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final Stream<O> timeout(final long timeout, final TimeUnit unit, final Publisher<? extends O> fallback) {
		final Timer timer = getTimer();
		Assert.state(timer != null, "Cannot use default timer as no environment has been provided to this " + "Stream");

		final Mono<Long> _timer = Mono.delay(timeout, unit == null ? TimeUnit.MILLISECONDS : unit, timer)
				.otherwiseJust(0L);
		final Function<O, Publisher<Long>> rest = new Function<O, Publisher<Long>>() {
			@Override
			public Publisher<Long> apply(O o) {
				return _timer;
			}
		};

		return timeout(_timer, rest, fallback);
	}

	/**
	 * Switch to the fallback Publisher if no data has been emitted when timeout publishers emit a signal <p> A Timeout
	 * Exception will be signaled if no data or complete signal have been sent within the given period.
	 *
	 * @param allTimeout the timeout emitter before the each signal from this sequence
	 *
	 * @return a new {@link Stream}
	 *
	 * @since 2.5
	 */
	public final <U> Stream<O> timeout(final Publisher<U> allTimeout) {
		return timeout(allTimeout, new Function<O, Publisher<U>>() {
			@Override
			public Publisher<U> apply(O o) {
				return allTimeout;
			}
		}, null);
	}

	/**
	 * Switch to the fallback Publisher if no data has been emitted when timeout publishers emit a signal <p> A Timeout
	 * Exception will be signaled if no data or complete signal have been sent within the given period.
	 *
	 * @param firstTimeout the timeout emitter before the first signal from this sequence
	 * @param followingTimeouts the timeout in unit between two notifications on this composable
	 *
	 * @return a new {@link Stream}
	 *
	 * @since 2.5
	 */
	public final <U, V> Stream<O> timeout(Publisher<U> firstTimeout,
			Function<? super O, ? extends Publisher<V>> followingTimeouts) {
		return timeout(firstTimeout, followingTimeouts, null);
	}

	/**
	 * Switch to the fallback Publisher if no data has been emitted when timeout publishers emit a signal <p> The
	 * current subscription will be cancelled and the fallback publisher subscribed. <p> A Timeout Exception will be
	 * signaled if no data or complete signal have been sent within the given period.
	 *
	 * @param firstTimeout the timeout emitter before the first signal from this sequence
	 * @param followingTimeouts the timeout in unit between two notifications on this composable
	 * @param fallback the fallback {@link Publisher} to subscribe to once the timeout has occured
	 *
	 * @return a new {@link Stream}
	 *
	 * @since 2.5
	 */
	public final <U, V> Stream<O> timeout(Publisher<U> firstTimeout,
			Function<? super O, ? extends Publisher<V>> followingTimeouts, final Publisher<? extends O>
			fallback) {
		if(fallback == null) {
			return new StreamTimeout<>(this, firstTimeout, followingTimeouts);
		}
		else{
			return new StreamTimeout<>(this, firstTimeout, followingTimeouts, fallback);
		}
	}

	/**
	 * Assign a Timer to be provided to this Stream Subscribers
	 *
	 * @param timer the timer
	 *
	 * @return a configured stream
	 */
	public Stream<O> timer(final Timer timer) {
		return new StreamSource<O, O>(this) {
			@Override
			public String getName() {
				return "timerSetup";
			}

			@Override
			public Timer getTimer() {
				return timer;
			}
		};

	}

	/**
	 * Create a new {@link Stream} that accepts a {@link reactor.fn.tuple.Tuple2} of T1 {@link Long} system time in
	 * millis and T2 {@link <T>} associated data
	 *
	 * @return a new {@link Stream} that emits tuples of millis time and matching data
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final Stream<Tuple2<Long, O>> timestamp() {
		return map(TIMESTAMP_OPERATOR);
	}

	/**
	 * Transform this {@link Stream} into a lazy {@link Iterable} blocking on next calls.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/toiterable.png" alt="">
	 * <p>
	 *
	 * @return a blocking {@link Iterable}
	 */
	public final Iterable<O> toIterable() {
		long c = getCapacity();
		return toIterable(c == -1L ? Long.MAX_VALUE : c);
	}

	/**
	 * Transform this {@link Stream} into a lazy {@link Iterable} blocking on next calls.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/toiterablen.png" alt="">
	 * <p>
	 *
	 * @return a blocking {@link Iterable}
	 */
	public final Iterable<O> toIterable(long batchSize) {
		return toIterable(batchSize, null);
	}

	/**
	 * Transform this {@link Stream} into a lazy {@link Iterable} blocking on next calls.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/toiterablen.png" alt="">
	 * <p>
	 *
	 * @return a blocking {@link Iterable}
	 */
	public final Iterable<O> toIterable(final long batchSize, Supplier<Queue<O>> queueProvider) {
		final Supplier<Queue<O>> provider;
		if(queueProvider == null){
			provider = QueueSupplier.get(batchSize);
		}
		else{
			provider = queueProvider;
		}
		return new BlockingIterable<>(this, batchSize, provider);
	}

	/**
	 *
	 *
	 * @return
	 */
	public final Iterator<O> toIterator() {
		return toIterable(1L).iterator();
	}

	/**
	 * Fetch all values in a List to the returned Mono
	 *
	 * @return the mono of all data from this Stream
	 *
	 * @since 2.0, 2.5
	 */
	@SuppressWarnings("unchecked")
	public final Mono<List<O>> toList() {
		return collect((Supplier<List<O>>) LIST_SUPPLIER, new BiConsumer<List<O>, O>() {
			@Override
			public void accept(List<O> o, O d) {
				o.add(d);
			}
		});
	}

	/**
	 * Convert all the sequence into a hashed map where the key is extracted by the given function and the value will be
	 * the most recent emitted item for this key.
	 *
	 * @param keyExtractor
	 *
	 * @return the mono of all data from this Stream
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public final <K> Mono<Map<K, O>> toMap(Function<? super O, ? extends K> keyExtractor) {
		return toMap(keyExtractor, (Function<O, O>)IDENTITY_FUNCTION);
	}

	/**
	 * Convert all the sequence into a hashed map where the key is extracted by the given function and the value will be
	 * the most recent extracted item for this key.
	 *
	 * @param keyExtractor
	 * @param valueExtractor
	 *
	 * @return the mono of all data from this Stream
	 *
	 * @since 2.5
	 */
	public final <K, V> Mono<Map<K, V>> toMap(Function<? super O, ? extends K> keyExtractor,
			Function<? super O, ? extends V> valueExtractor) {
		return toMap(keyExtractor, valueExtractor, new Supplier<Map<K, V>>() {
			@Override
			public Map<K, V> get() {
				return new HashMap<>();
			}
		});
	}

	/**
	 * Convert all the sequence into a supplied map where the key is extracted by the given function and the value will
	 * be the most recent extracted item for this key.
	 *
	 * @param keyExtractor
	 * @param valueExtractor
	 * @param mapSupplier
	 *
	 * @return the mono of all data from this Stream
	 *
	 * @since 2.5
	 */
	public final <K, V> Mono<Map<K, V>> toMap(
			final Function<? super O, ? extends K> keyExtractor,
			final Function<? super O, ? extends V> valueExtractor,
			Supplier<Map<K, V>> mapSupplier) {
		Objects.requireNonNull(keyExtractor, "Key extractor is null");
		Objects.requireNonNull(valueExtractor, "Value extractor is null");
		Objects.requireNonNull(mapSupplier, "Map supplier is null");
		return collect(mapSupplier, new BiConsumer<Map<K, V>, O>() {
			@Override
			public void accept(Map<K, V> m, O d) {
				m.put(keyExtractor.apply(d), valueExtractor.apply(d));
			}
		});
	}

	/**
	 * Convert all the sequence into a hashed map where the key is extracted by the given function and the value will be
	 * all the emitted item for this key.
	 *
	 * @param keyExtractor
	 *
	 * @return the mono of all data from this Stream
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public final <K> Mono<Map<K, Collection<O>>> toMultimap(Function<? super O, ? extends K> keyExtractor) {
		return toMultimap(keyExtractor, (Function<O, O>)IDENTITY_FUNCTION);
	}

	/**
	 * Convert all the sequence into a hashed map where the key is extracted by the given function and the value will be
	 * all the extracted items for this key.
	 *
	 * @param keyExtractor
	 * @param valueExtractor
	 *
	 * @return the mono of all data from this Stream
	 *
	 * @since 2.5
	 */
	public final <K, V> Mono<Map<K, Collection<V>>> toMultimap(Function<? super O, ? extends K> keyExtractor,
			Function<? super O, ? extends V> valueExtractor) {
		return toMultimap(keyExtractor, valueExtractor, new Supplier<Map<K, Collection<V>>>() {
			@Override
			public Map<K, Collection<V>> get() {
				return new HashMap<>();
			}
		});
	}

	/**
	 * Convert all the sequence into a supplied map where the key is extracted by the given function and the value will
	 * be all the extracted items for this key.
	 *
	 * @param keyExtractor
	 * @param valueExtractor
	 * @param mapSupplier
	 *
	 * @return the mono of all data from this Stream
	 *
	 * @since 2.5
	 */
	public final <K, V> Mono<Map<K, Collection<V>>> toMultimap(
			final Function<? super O, ? extends K> keyExtractor,
			final Function<? super O, ? extends V> valueExtractor,
			Supplier<Map<K, Collection<V>>> mapSupplier) {
		Objects.requireNonNull(keyExtractor, "Key extractor is null");
		Objects.requireNonNull(valueExtractor, "Value extractor is null");
		Objects.requireNonNull(mapSupplier, "Map supplier is null");
		return collect(mapSupplier, new BiConsumer<Map<K, Collection<V>>, O>() {
			@Override
			public void accept(Map<K, Collection<V>> m, O d) {
				K key = keyExtractor.apply(d);
				Collection<V> values = m.get(key);
				if(values == null){
					values = new ArrayList<>();
					m.put(key, values);
				}
				values.add(valueExtractor.apply(d));
			}
		});
	}

	/**
	 * Make this {@link Stream} subscribers unbounded
	 *
	 * @return {@link Stream} with capacity set to max
	 *
	 * @see #capacity(long)
	 */
	public final Stream<O> unbounded() {
		return capacity(Long.MAX_VALUE);
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Stream} every pre-defined {@param backlog} times. The
	 * nested streams will be pushed into the returned {@link Stream}.
	 *
	 * @param backlog the time period when each window close and flush the attached consumer
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 *
	 * @since 2.0
	 */
	public final Stream<Stream<O>> window(final int backlog) {
		return new StreamWindow<>(this, backlog, QueueSupplier.<O>get(backlog));
	}

	/**
	 * Re-route incoming values into bucket streams that will be pushed into the returned {@link Stream} every {@code
	 * skip} and complete every time {@code maxSize} has been reached by any of them. Complete signal will flush any
	 * remaining buckets.
	 *
	 * @param skip the number of items to skip before creating a new bucket
	 * @param maxSize the collected size
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 */
	public final Stream<Stream<O>> window(final int maxSize, final int skip) {
		return new StreamWindow<>(this,
				maxSize,
				skip,
				QueueSupplier.<O>xs(),
				QueueSupplier.<UnicastProcessor<O>>xs());
	}

	/**
	 * Re-route incoming values into bucket streams that will be pushed into the returned {@link Stream} every  and
	 * complete every time {@code boundarySupplier} emits an item.
	 *
	 * @param boundarySupplier the the stream to listen to for separating each window
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 */
	public final Stream<Stream<O>> window(final Publisher<?> boundarySupplier) {
		return new StreamWindowBoundary<>(this,
				boundarySupplier,
				QueueSupplier.<O>xs(),
				QueueSupplier.xs());
	}

	/**
	 * Re-route incoming values into bucket streams that will be pushed into the returned {@link Stream} every and
	 * complete every time {@code boundarySupplier} stream emits an item. Window starts forwarding when the
	 * bucketOpening stream emits an item, then subscribe to the boundary supplied to complete.
	 *
	 * @param bucketOpening the publisher to listen for signals to create a new window
	 * @param boundarySupplier the factory to create the stream to listen to for closing an open window
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 */
	public final <U, V> Stream<Stream<O>> window(final Publisher<U> bucketOpening,
			final Function<? super U, ? extends Publisher<V>> boundarySupplier) {

		long c = getCapacity();
		c = c == -1L ? Long.MAX_VALUE : c;
		/*if(c > 1 && c < 10_000_000){
			return new StreamWindowBeginEnd<>(this,
					bucketOpening,
					boundarySupplier,
					QueueSupplier.get(c),
					(int)c);
		}*/

		return new StreamWindowStartEnd<>(this,
				bucketOpening,
				boundarySupplier,
				QueueSupplier.get(c), QueueSupplier.<O>xs());
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Stream} every pre-defined timespan. The nested streams
	 * will be pushed into the returned {@link Stream}.
	 *
	 * @param timespan the period in unit to use to release a new window as a Stream
	 * @param unit the time unit
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 *
	 * @since 2.0
	 */
	public final Stream<Stream<O>> window(long timespan, TimeUnit unit) {
		Timer t = getTimer();
		if(t == null) t = Timer.global();
		return window(interval(timespan, unit, t));
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Stream} every pre-defined timespan OR maxSize items.
	 * The nested streams will be pushed into the returned {@link Stream}.
	 *
	 * @param maxSize the max collected size
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit the time unit
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 *
	 * @since 2.0
	 */
	public final Stream<Stream<O>> window(final int maxSize, final long timespan, final TimeUnit unit) {
		return new StreamWindowTimeOrSize<>(this, maxSize, timespan, unit, getTimer());
	}

	/**
	 * Re-route incoming values into bucket streams that will be pushed into the returned {@link Stream} every {@code
	 * timeshift} period. These streams will complete every {@code timespan} period has cycled. Complete signal will
	 * flush any remaining buckets.
	 *
	 * @param timespan the period in unit to use to complete a window
	 * @param timeshift the period in unit to use to create a new window
	 * @param unit the time unit
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 */
	public final Stream<Stream<O>> window(final long timespan, final long timeshift, final TimeUnit unit) {
		if (timeshift == timespan) {
			return window(timespan, unit);
		}

		Timer t = getTimer();
		if(t == null) t = Timer.global();
		final Timer timer = t;

		return window(interval(0L, timeshift, unit, timer), new Function<Long, Publisher<Long>>() {
			@Override
			public Publisher<Long> apply(Long aLong) {
				return Mono.delay(timespan, unit, timer);
			}
		});
	}

	/**
	 * Combine the most recent items from this sequence and the passed sequence.
	 *
	 * @param other
	 * @param <U>
	 *
	 * @return
	 */
	public final <U, R> Stream<R> withLatestFrom(Publisher<? extends U> other, BiFunction<? super O, ? super U, ?
			extends R > resultSelector){
		return new StreamWithLatestFrom<>(this, other, resultSelector);
	}

	/**
	 * Pass all the nested {@link Publisher} values to a new {@link Stream} until one of them complete. The result will
	 * be produced by the zipper transformation from a tuple of each upstream most recent emitted data.
	 *
	 * @return the zipped stream
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final <T2, V> Stream<V> zipWith(Iterable<? extends T2> iterable,
			BiFunction<? super O, ? super T2, ? extends V> zipper) {
		return zipWithIterable(iterable, zipper);
	}

	/**
	 * Pass all the nested {@link Publisher} values to a new {@link Stream} until one of them complete. The result will
	 * be produced by the zipper transformation from a tuple of each upstream most recent emitted data.
	 *
	 * @return the zipped stream
	 *
	 * @since 2.5
	 */
	public final <T2> Stream<Tuple2<O, T2>> zipWith(Iterable<? extends T2> iterable) {
		return zipWithIterable(iterable);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * produced by the passed combinator from the most recent items emitted by each source until any of them
	 * completes. Errors will immediately be forwarded.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zip.png" alt="">
	 * <p>
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <T2> type of the value from source2
	 * @param <V> The produced output after transformation by the combinator
	 *
	 * @return a zipped {@link Stream}
	 *
	 * @since 2.0
	 */
	public final <T2, V> Stream<V> zipWith(final Publisher<? extends T2> source2,
			final BiFunction<? super O, ? super T2, ? extends V> combinator) {
		return StreamSource.wrap(Flux.zip(this, source2, combinator));
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipt.png" alt="">
	 * <p>
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param <T2> type of the value from source2
	 *
	 * @return a zipped {@link Stream}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public final <T2> Stream<Tuple2<O, T2>> zipWith(final Publisher<? extends T2> source2) {
		return StreamSource.wrap(Flux.<O, T2, Tuple2<O, T2>>zip(this, source2, TUPLE2_BIFUNCTION));
	}

	/**
	 * Pass all the nested {@link Publisher} values to a new {@link Stream} until one of them complete. The result will
	 * be produced by the zipper transformation from a tuple of each upstream most recent emitted data.
	 *
	 * @return the zipped stream
	 *
	 * @since 2.5
	 */
	public final <T2, V> Stream<V> zipWithIterable(Iterable<? extends T2> iterable,
			BiFunction<? super O, ? super T2, ? extends V> zipper) {
		return new StreamZipIterable<>(this, iterable, zipper);
	}

	/**
	 * Pass all the nested {@link Publisher} values to a new {@link Stream} until one of them complete. The result will
	 * be produced by the zipper transformation from a tuple of each upstream most recent emitted data.
	 *
	 * @return the zipped stream
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public final <T2> Stream<Tuple2<O, T2>> zipWithIterable(Iterable<? extends T2> iterable) {
		return new StreamZipIterable<>(this, iterable, (BiFunction<O, T2, Tuple2<O, T2>>)TUPLE2_BIFUNCTION);
	}

	static final BiFunction JOIN_BIFUNCTION = new BiFunction<Object, Object, List>() {
		@Override
		public List<?> apply(Object t1, Object t2) {
			return Arrays.asList(t1, t2);
		}
	};
	static final BooleanSupplier ALWAYS_BOOLEAN_SUPPLIER = new BooleanSupplier() {
		@Override
		public boolean getAsBoolean() {
			return true;
		}
	};
	static final Predicate ALWAYS_PREDICATE = new Predicate() {
		@Override
		public boolean test(Object o) {
			return true;
		}
	};
	static final Function HASHCODE_EXTRACTOR  = new Function<Object, Integer>() {
		@Override
		public Integer apply(Object t1) {
			return t1.hashCode();
		}
	};
	static final Supplier LIST_SUPPLIER = new Supplier() {
		@Override
		public Object get() {
			return new ArrayList<>();
		}
	};
	static final Supplier   SET_SUPPLIER       = new Supplier() {
		@Override
		public Object get() {
			return new HashSet<>();
		}
	};
	static final Function   TIMESTAMP_OPERATOR = new Function<Object, Tuple2<Long, ?>>() {
		@Override
		public Tuple2<Long, ?> apply(Object o) {
			return Tuple.of(System.currentTimeMillis(), o);
		}
	};
	static final Stream   NEVER             = from(Flux.never());
	static final Function IDENTITY_FUNCTION = new Function() {
		@Override
		public Object apply(Object o) {
			return o;
		}
	};
	static final BiFunction TUPLE2_BIFUNCTION  = new BiFunction() {
		@Override
		public Tuple2 apply(Object t1, Object t2) {
			return Tuple.of(t1, t2);
		}
	};
	static final Function JOIN_FUNCTION = new Function<Object[], Object>() {
		@Override
		public Object apply(Object[] objects) {
			return Arrays.asList(objects);
		}
	};

	@SuppressWarnings("unchecked")
	static BooleanSupplier countingBooleanSupplier(final BooleanSupplier predicate, final long max) {
		if (max == 0) {
			return predicate;
		}
		return new BooleanSupplier() {
			long n;

			@Override
			public boolean getAsBoolean() {
				return n++ < max && predicate.getAsBoolean();
			}
		};
	}

	@SuppressWarnings("unchecked")
	static <O> Predicate<O> countingPredicate(final Predicate<O> predicate, final long max) {
		if (max == 0) {
			return predicate;
		}
		return new Predicate<O>() {
			long n;

			@Override
			public boolean test(O o) {
				return n++ < max && predicate.test(o);
			}
		};
	}

	@SuppressWarnings("unchecked")
	static <O> Supplier<Set<O>> hashSetSupplier() {
		return (Supplier<Set<O>>) SET_SUPPLIER;
	}


}