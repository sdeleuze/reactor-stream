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
 * A Reactive Stream {@link Publisher} implementing a complete scope of Reactive Extensions.
 * <p>
 * A {@link Stream} is a sequence of 0..N events flowing via callbacks to {@link Subscriber#onNext(Object)}.
 * Static source generators are available and allow for transfromation from functional callbacks or plain Java types
 * like {@link Iterable} or {@link Future}.
 * Instance methods will build new templates of {@link Stream} also called operators. Their role is to build a
 * delegate
 * chain of
 * {@link Subscriber} materialized only when {@link Stream#subscribe}
 * is called. Materialization will operate by propagating a
 * {@link Subscription} from the root {@link Publisher} via {@link Subscriber#onSubscribe(Subscription)}.
 *
 *
 * This API is not directly an extension of
 * {@link Flux} but considerably expands its scope. and conversions between {@link Stream} and {@link Flux} can
 * be achieved using the operator {@link #as} : {@code flux.as(Stream::from)}.
 * <p>
 * <img width="640" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/stream.png" alt="">
 * <p>
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
	 *
	 * @param period The the time relative to given unit to wait before the next increment
	 * @param unit The unit of time
	 *
	 * @return a new timed {@link Stream}
	 */
	public static Stream<Long> interval(long period, TimeUnit unit) {
		return interval(-1L, period, unit, Timer.globalOrNew());
	}

	/**
	 * Create a new {@link Stream} that emits an ever incrementing long starting with 0 every N period of time unit on
	 * the given timer. If demand is not produced in time, an onError will be signalled. The {@link Stream} will never
	 * complete.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/interval.png" alt="">
	 *
	 * @param period the period in [unit] before each following increment
	 * @param unit   the time unit
	 * @param timer  the {@link Timer} to schedule on
	 *
	 * @return a new timed {@link Stream}
	 */
	public static Stream<Long> interval(long period, TimeUnit unit, Timer timer) {
		return interval(-1L, period, unit, timer);
	}

	/**
	 * Create a new {@link Stream} that emits an ever incrementing long starting with 0 every N period of time unit on
	 * a global timer. If demand is not produced in time, an onError will be signalled. The {@link Stream} will never
	 * complete.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/intervald.png" alt="">
	 *
	 * @param delay  the timespan in [unit] to wait before emitting 0l
	 * @param period the period in [unit] before each following increment
	 * @param unit   the time unit
	 *
	 * @return a new timed {@link Stream}
	 */
	public static Stream<Long> interval(long delay, long period, TimeUnit unit) {
		return interval(delay, period, unit, Timer.globalOrNew());
	}

	/**
	 * Create a new {@link Stream} that emits an ever incrementing long starting with 0 every N period of time unit on
	 * the given timer. If demand is not produced in time, an onError will be signalled. The {@link Stream} will never
	 * complete.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/intervald.png" alt="">
	 *
	 * @param delay  the timespan in [unit] to wait before emitting 0l
	 * @param period the period in [unit] before each following increment
	 * @param unit   the time unit
	 * @param timer  the {@link Timer} to schedule on
	 *
	 * @return a new timed {@link Stream}
	 */
	public static Stream<Long> interval(long delay, long period, TimeUnit unit, Timer timer) {
		return new StreamInterval(TimeUnit.MILLISECONDS.convert(delay, unit), period, unit, timer);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * produced by the
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
	 * produced by the
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
	 * Emit a single boolean true if all values of this sequence match
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
	 * Delay this {@link Stream} signals to {@link Subscriber#onNext} until the given period in seconds elapses.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/delayonnext.png" alt="">
	 *
	 * @param seconds period to delay each {@link Subscriber#onNext} call
	 *
	 * @return a throttled {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> delay(long seconds) {
		return delay(seconds, TimeUnit.SECONDS);
	}


	/**
	 * Delay this {@link Stream} signals to {@link Subscriber#onNext} until the given period elapses.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/delayonnext.png" alt="">
	 *
	 * @param delay period to delay each {@link Subscriber#onNext} call
	 * @param unit unit of time
	 *
	 * @return a throttled {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> delay(final long delay, final TimeUnit unit) {
		return concatMap(new Function<O, Publisher<? extends O>>() {
			@Override
			public Publisher<? extends O> apply(final O o) {
				Timer timer = getTimer();
				return Mono.delay(delay, unit, timer != null ? timer : Timer.globalOrNew())
				           .map(new Function<Long, O>() {
					@Override
					public O apply(Long aLong) {
						return o;
					}
				});
			}
		});
	}

	/**
	 * Delay the {@link Stream#subscribe(Subscriber) subscription} to this {@link Stream} source until the given
	 * period elapses.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/delaysubscription.png" alt="">
	 *
	 * @param delay period in seconds before subscribing this {@link Stream}
	 *
	 * @return a delayed {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> delaySubscription(long delay) {
		return delaySubscription(delay, TimeUnit.SECONDS);
	}

	/**
	 * Delay the {@link Stream#subscribe(Subscriber) subscription} to this {@link Stream} source until the given
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
		Timer timer = getTimer();
		return delaySubscription(Mono.delay(delay, unit, timer != null ? timer : Timer.globalOrNew()));
	}

	/**
	 * Delay the subscription to the main source until another Publisher
	 * signals a value or completes.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/delaysubscriptionp.png" alt="">
	 *
	 * @param subscriptionDelay a
	 * {@link Publisher} to signal by next or complete this {@link Stream#subscribe(Subscriber)}
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
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/dispatchon.png" alt="">
	 * <p>
	 * {@code stream.dispatchOn(ForkJoinPool.commonPool()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param executorService an {@link ExecutorService} to dispatch events downstream
	 *
	 * @return a {@link Stream} consuming asynchronously
	 */
	public final Stream<O> dispatchOn(final ExecutorService executorService) {
		return dispatchOn(new ExecutorServiceScheduler(executorService));
	}

	/**
	 * For each {@link Subscriber}, tracks this {@link Stream} values that have been seen and
	 * filters out duplicates.
	 *
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
	 * Filters out subsequent and repeated elements.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/distinctuntilchanged.png" alt="">

	 *
	 * @return a new {@link Stream} with conflated repeated elements
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final Stream<O> distinctUntilChanged() {
		return new StreamDistinctUntilChanged<O, O>(this, HASHCODE_EXTRACTOR);
	}

	/**
	 * Filters out subsequent and repeated elements provided a matching extracted key.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/distinctuntilchangedk.png" alt="">

	 *
	 * @param keySelector function to compute comparison key for each element
	 *
	 * @return a new {@link Stream} with conflated repeated elements given a comparison key
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
	 * Triggered when the {@link Stream} completes with an error matching the given exception type.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonerrorw.png" alt="">
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
	 * Triggered when the {@link Stream} completes with an error matching the given exception type.
	 * Provide a non null second argument to the {@link BiConsumer} if the captured {@link Exception} wraps a final
	 * cause value.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonerrorv.png" alt="">
	 *
	 * @param exceptionType the type of exceptions to handle
	 * @param onError the error handler for each error
	 * @param <E> type of the error to handle
	 *
	 * @return a new unaltered {@link Stream}
	 */
	public final <E extends Throwable> Stream<O> doOnValueError(final Class<E> exceptionType,
			final BiConsumer<Object, ? super E> onError) {
		Objects.requireNonNull(exceptionType, "Error type must be provided");
		Objects.requireNonNull(onError, "Error callback must be provided");
		return doOnError(new Consumer<Throwable>() {
			@Override
			@SuppressWarnings("unchecked")
			public void accept(Throwable cause) {
				if (exceptionType.isAssignableFrom(cause.getClass())) {
					onError.accept(Exceptions.getFinalValueCause(cause), (E) cause);
				}
			}
		});
	}

	/**
	 * Map this {@link Stream} sequence into {@link reactor.fn.tuple.Tuple2} of T1 {@link Long} timemillis and T2
	 * {@link <T>} associated data. The timemillis corresponds to the elapsed time between the subscribe and the first
	 * next signal OR between two next signals.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/elapsed.png" alt="">
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
	 * Emit only the element at the given index position or {@link IndexOutOfBoundsException} if the sequence is shorter.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/elementat.png" alt="">
	 *
	 * @param index index of an item
	 *
	 * @return a {@link Mono} of the item at a specified index
	 */
	public final Mono<O> elementAt(final int index) {
		return new MonoElementAt<O>(this, index);
	}

	/**
	 * Emit only the element at the given index position or signals a
	 * default value if specified if the sequence is shorter.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/elementatd.png" alt="">
	 *
	 * @param index index of an item
	 * @param defaultValue supply a default value if not found
	 *
	 * @return a {@link Mono} of the item at a specified index or a default value
	 */
	public final Mono<O> elementAtOrDefault(final int index, final Supplier<? extends O> defaultValue) {
		return new MonoElementAt<>(this, index, defaultValue);
	}

	/**
	 * Emit only the last value of each batch counted from this {@link Stream} sequence.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/every.png" alt="">
	 *
	 * @param batchSize the batch size to count
	 *
	 * @return a new {@link Stream} whose values are the last value of each batch
	 */
	public final Stream<O> every(final int batchSize) {
		return new StreamEvery<O>(this, batchSize);
	}

	/**
	 * Emit only the first value of each batch counted from this {@link Stream} sequence.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/everyfirst.png" alt="">
	 *
	 * @param batchSize the batch size to use
	 *
	 * @return a new {@link Stream} whose values are the first value of each batch
	 */
	public final Stream<O> everyFirst(final int batchSize) {
		return new StreamEvery<O>(this, batchSize, true);
	}

	/**
	 * Emit a single boolean true if any of the values of this {@link Stream} sequence match
	 * the predicate.
	 * <p>
	 * The implementation uses short-circuit logic and completes with true if
	 * the predicate matches a value.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/exists.png" alt="">
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
	 * passed into the new {@link Stream}. If the predicate test fails, the value is ignored and a request of 1 is 
	 * emitted.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/filter.png" alt="">
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
	 *
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
	 * Transform the items emitted by this {@link Stream} into Publishers, then flatten the emissions from those by
	 * merging them into a single {@link Stream}, so that they may interleave. The concurrency argument allows to
	 * control how many merged {@link Publisher} can happen in parallel.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/flatmapc.png" alt="">
	 *
	 * @param mapper the {@link Function} to transform input sequence into N sequences {@link Publisher}
	 * @param concurrency the maximum in-flight elements from this {@link Stream} sequence
	 * @param <V> the merged output sequence type
	 *
	 * @return a new {@link Stream}
	 *
	 * @since 2.5
	 */
	public final <V> Stream<V> flatMap(final Function<? super O, ? extends Publisher<? extends V>> mapper, int
			concurrency) {
		return flatMap(mapper, concurrency, PlatformDependent.XS_BUFFER_SIZE);
	}

	/**
	 * Transform the items emitted by this {@link Stream} into Publishers, then flatten the emissions from those by
	 * merging them into a single {@link Stream}, so that they may interleave. The concurrency argument allows to
	 * control how many merged {@link Publisher} can happen in parallel. The prefetch argument allows to give an
	 * arbitrary prefetch size to the merged {@link Publisher}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/flatmapc.png" alt="">
	 *
	 * @param mapper the {@link Function} to transform input sequence into N sequences {@link Publisher}
	 * @param concurrency the maximum in-flight elements from this {@link Stream} sequence
	 * @param prefetch the maximum in-flight elements from each inner {@link Publisher} sequence
	 * @param <V> the merged output sequence type
	 *
	 * @return a new {@link Stream}
	 *
	 * @since 2.5
	 */
	public final <V> Stream<V> flatMap(final Function<? super O, ? extends Publisher<? extends V>> mapper, int
			concurrency, int prefetch) {
		return StreamSource.wrap(Flux.flatMap(this,
				mapper,
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
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/forkjoin.png" alt="">
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
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/forkjoin.png" alt="">
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
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/forkjoin.png" alt="">
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
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/forkjoin.png" alt="">
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
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/forkjoin.png" alt="">
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
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/forkjoin.png" alt="">
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
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/forkjoin.png" alt="">
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
	 * Re-route this sequence into dynamically created {@link Stream} for each unique key evaluated by the given
	 * key mapper.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/groupby.png" alt="">
	 *
	 * @param keyMapper the key mapping {@link Function} that evaluates an incoming data and returns a key.
	 *
	 * @return a {@link Stream} of {@link GroupedStream} grouped sequences
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final <K> Stream<GroupedStream<K, O>> groupBy(final Function<? super O, ? extends K> keyMapper) {
		return groupBy(keyMapper, (Function<O, O>)IDENTITY_FUNCTION);
	}

	/**
	 * Re-route this sequence into dynamically created {@link Stream} for each unique key evaluated by the given
	 * key mapper. It will use the given value mapper to extract the element to route.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/groupby.png" alt="">
	 *
	 * @param keyMapper the key mapping function that evaluates an incoming data and returns a key.
	 * @param valueMapper the value mapping function that evaluates which data to extract for re-routing.
	 *
	 * @return a {@link Stream} of {@link GroupedStream} grouped sequences
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
	 * Emit a single boolean true if this {@link Stream} sequence has at least one element.
	 * <p>
	 * The implementation uses short-circuit logic and completes with true on onNext.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/haselements.png" alt="">
	 *
	 * @return a new {@link Stream} with <code>true</code> if any value is emitted and <code>false</code>
	 * otherwise
	 */
	public final Mono<Boolean> hasElements() {
		return new MonoHasElements<>(this);
	}

	/**
	 * Hides the identities of this {@link Stream} and its {@link Subscription}
	 * as well.
	 *
	 * @return a new {@link Stream} defeating any {@link Publisher} / {@link Subscription} feature-detection
	 */
	public final Stream<O> hide() {
		return new StreamHide<>(this);
	}

	/**
	 * Ignores onNext signals (dropping them) and only reacts on termination.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/ignoreelements.png" alt="">
	 * <p>
	 *
	 * @return a new completable {@link Mono}.
	 */
	public final Mono<O> ignoreElements() {
		return Mono.ignoreElements(this);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * produced in an indexed {@link List} by the most recent items emitted by each source until any of them completes.
	 * Errors will immediately be forwarded.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/join.png" alt="">
	 *
	 * @param other the index [1] {@link Publisher} to zip with
	 * @param <T> the source collected type
	 *
	 * @return a zipped {@link Stream} as {@link List}
	 */
	@SuppressWarnings("unchecked")
	public final <T> Stream<List<T>> joinWith(Publisher<T> other) {
		return zipWith(other, (BiFunction<Object, Object, List<T>>) JOIN_BIFUNCTION);
	}

	/**
	 * Signal the last element observed before complete signal.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/last.png" alt="">
	 *
	 * @return a new limited {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Mono<O> last() {
		return MonoSource.wrap(new StreamTakeLast<>(this, 1));
	}

	/**
	 * Intercept all source signals with the returned Subscriber that might choose to pass them
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
	 * Observe Reactive Streams signals matching the passed flags {@code options} and use {@link Logger} support to
	 * handle trace
	 * implementation. Default will use java.util.logging. If SLF4J is available, it will be used instead.
	 *
	 * Options allow fine grained filtering of the traced signal, for instance to only capture onNext and onError:
	 * <pre>
	 *     stream.log("category", Logger.ON_NEXT | LOGGER.ON_ERROR)
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/log.png" alt="">
	 *
	 * @param category to be mapped into logger configuration (e.g. org.springframework.reactor).
	 * @param options a flag option that can be mapped with {@link Logger#ON_NEXT} etc.
	 *
	 * @return a new unaltered {@link Stream}
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
	 *
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
	 * Transform the incoming onNext, onError and onComplete signals into {@link reactor.rx.Signal}.
	 * Since the error is materialized as a {@code Signal}, the propagation will be stopped and onComplete will be
	 * emitted. Complete signal will first emit a {@code Signal.complete()} and then effectively complete the stream.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/materialize.png" alt="">
	 *
	 * @return a {@link Stream} of materialized {@link Signal}
	 */
	public final Stream<Signal<O>> materialize() {
		return new StreamMaterialize<>(this);
	}

	/**
	 * Merge emissions of this {@link Stream} with the provided {@link Publisher}, so that they may interleave.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/merge.png" alt="">
	 *
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
	 * Prepare a {@link ConnectableStream} which shares this {@link Stream} sequence and dispatches values to 
	 * subscribers in a backpressure-aware manner. Prefetch will default to {@link PlatformDependent#SMALL_BUFFER_SIZE}.
	 * This will effectively turn any type of sequence into a hot sequence.
	 *
	 * <p>
	 * Backpressure will be coordinated on {@link Subscription#request} and if any {@link Subscriber} is missing
	 * demand (requested = 0), multicast will pause pushing/pulling.
	 * 
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/multicast.png" alt="">
	 *
	 * @return a new {@link ConnectableStream} whose values are broadcasted to all subscribers once connected
	 *
	 * @since 2.5
	 */
	public final ConnectableStream<O> multicast() {
		return publish();
	}

	/**
	 * Prepare a
	 * {@link ConnectableStream} which subscribes this {@link Stream} sequence to the given {@link Processor}.
	 * The {@link Processor} will be itself subscribed by child {@link Subscriber} when {@link ConnectableStream#connect()}
	 *  is invoked manually or automatically via {@link ConnectableStream#autoConnect} and {@link ConnectableStream#refCount}.
	 *  Note that some {@link Processor} do not support multi-subscribe, multicast is non opinionated in fact and
	 *  focuses on subscribe lifecycle.
	 *
	 * This will effectively turn any type of sequence into a hot sequence by sharing a single {@link Subscription}.
	 * <p> The {@link Processor} will not be specifically reusable and multi-connect might not work as expected
	 * depending on the {@link Processor}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/multicastp.png" alt="">
	 *
	 * @param processor the {@link Processor} reference to subscribe to this {@link Stream} and share.
	 *
	 * @return a new {@link ConnectableStream} whose values are broadcasted to supported subscribers once connected via {@link Processor}
	 *
	 * @since 2.5
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
	 * Prepare a
	 * {@link ConnectableStream} which subscribes this {@link Stream} sequence to a supplied {@link Processor}
	 * when
	 * {@link ConnectableStream#connect()} is invoked manually or automatically via {@link ConnectableStream#autoConnect} and {@link ConnectableStream#refCount}.
	 * The {@link Processor} will be itself subscribed by child {@link Subscriber}.
	 *  Note that some {@link Processor} do not support multi-subscribe, multicast is non opinionated in fact and
	 *  focuses on subscribe lifecycle.
	 *
	 * This will effectively turn any type of sequence into a hot sequence by sharing a single {@link Subscription}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/multicastp.png" alt="">
	 *
	 * @param processorSupplier the {@link Processor} {@link Supplier} to call, subscribe to this {@link Stream} and
	 * share.
	 *
	 * @return a new {@link ConnectableStream} whose values are broadcasted to supported subscribers once connected via {@link Processor}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public final ConnectableStream<O> multicast(
			Supplier<? extends Processor<? super O, ? extends O>> processorSupplier) {
		return multicast(processorSupplier, IDENTITY_FUNCTION);
	}

	/**
	 * Prepare a
	 * {@link ConnectableStream} which subscribes this {@link Stream} sequence to the given {@link Processor}.
	 * The {@link Processor} will be itself subscribed by child {@link Subscriber} when {@link ConnectableStream#connect()}
	 *  is invoked manually or automatically via {@link ConnectableStream#autoConnect} and {@link ConnectableStream#refCount}.
	 *  Note that some {@link Processor} do not support multi-subscribe, multicast is non opinionated in fact and
	 *  focuses on subscribe lifecycle.
	 *
	 * This will effectively turn any type of sequence into a hot sequence by sharing a single {@link Subscription}.
	 * <p> The {@link Processor} will not be specifically reusable and multi-connect might not work as expected
	 * depending on the {@link Processor}.
	 *
	 * <p> The selector will be applied once per {@link Subscriber} and can be used to blackbox pre-processing.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/multicastp.png" alt="">
	 *
	 * @param processor the {@link Processor} reference to subscribe to this {@link Stream} and share.
	 * @param selector a {@link Function} receiving a {@link Stream} derived from the supplied {@link Processor} and
	 * returning the end {@link Publisher} subscribed by a unique {@link Subscriber}
	 * @param <U> produced type from the given selector
	 *
	 * @return a new {@link ConnectableStream} whose values are broadcasted to supported subscribers once connected via {@link Processor}
	 * 
	 * @since 2.5
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
	 /**
	 * Prepare a
	 * {@link ConnectableStream} which subscribes this {@link Stream} sequence to a supplied {@link Processor}
	 * when
	 * {@link ConnectableStream#connect()} is invoked manually or automatically via {@link ConnectableStream#autoConnect} and {@link ConnectableStream#refCount}.
	 * The {@link Processor} will be itself subscribed by child {@link Subscriber}.
	 *  Note that some {@link Processor} do not support multi-subscribe, multicast is non opinionated in fact and
	 *  focuses on subscribe lifecycle.
	 *
	 * This will effectively turn any type of sequence into a hot sequence by sharing a single {@link Subscription}.
	 * <p> The selector will be applied once per {@link Subscriber} and can be used to blackbox pre-processing.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/multicastp.png" alt="">
	 *
	 * @param processorSupplier the {@link Processor} {@link Supplier} to call, subscribe to this {@link Stream} and
	 * share.
	 * @param selector a {@link Function} receiving a {@link Stream} derived from the supplied {@link Processor} and
	 * returning the end {@link Publisher} subscribed by a unique {@link Subscriber}
	 * @param <U> produced type from the given selector
	 *
	 * @return a new {@link ConnectableStream} whose values are broadcasted to supported subscribers once connected via {@link Processor}
	 *
	 * @since 2.5
	 */
	public final <U> ConnectableStream<U> multicast(Supplier<? extends Processor<? super O, ? extends O>>
			processorSupplier, Function<Stream<O>, ? extends Publisher<? extends U>> selector) {
		return new StreamMulticast<>(this, processorSupplier, selector);
	}

	/**
	 * Make this
	 * {@link Stream} subscribed N concurrency times for each child {@link Subscriber}. In effect, if this {@link Stream}
	 * is a cold replayable source, duplicate sequences will be emitted to the passed {@link GroupedStream} partition
	 * . If this {@link Stream} is a hot sequence, {@link GroupedStream} partitions might observe different values, e
	 * .g. subscribing to a {@link reactor.core.publisher.WorkQueueProcessor}.
	 * <p>Each partition is merged back using {@link #flatMap flatMap} and the result sequence might be interleaved.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/multiplex.png" alt="">
	 *
	 * @param fn the indexed via
	 * {@link GroupedStream#key()} sequence transformation to be merged in the returned {@link Stream}
	 * @param <V> the type of the return value of the transformation function
	 *
	 * @return a merged {@link Stream} produced from N concurrency transformed sequences
	 *
	 * @since 2.5
	 */
	public final <V> Stream<V> multiplex(final int concurrency,
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

	/**
	 * Emit the current instance of the {@link Stream}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/nest.png" alt="">
	 *
	 * @return a new {@link Stream} whose only value will be the current {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<Stream<O>> nest() {
		return just(this);
	}

	/**
	 * Emit only the first observed item from this {@link Stream} sequence.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/next.png" alt="">
	 * <p>
	 *
	 * @return a new {@link Mono}
	 *
	 * @since 2.0, 2.5
	 */
	public final Mono<O> next() {
		return Mono.from(this);
	}

	/**
	 * Request an unbounded demand and block incoming values if not enough demand is signaled downstream.
	 * <p> Blocking a synchronous {@link Stream} might lead to unexpected starvation of downstream request
	 * replenishing or upstream hot event producer.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressureblock.png" alt="">
	 *
	 * @return a blocking {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> onBackpressureBlock() {
		return onBackpressureBlock(WaitStrategy.blocking());
	}

	/**
	 * Request an unbounded demand and block incoming onNext signals if not enough demand is requested downstream.
	 * <p> Blocking a synchronous {@link Stream} might lead to unexpected starvation of downstream request
	 * replenishing or upstream hot event producer.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressureblock.png" alt="">
	 *
	 * @param waitStrategy a {@link WaitStrategy} to trade off higher latency blocking wait for CPU resources
	 * (spinning, yielding...)
	 *
	 * @return a blocking {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> onBackpressureBlock(WaitStrategy waitStrategy) {
		return new StreamBlock<>(this, waitStrategy);
	}

	/**
	 * Request an unbounded demand and push the returned {@link Stream}, or park the observed elements if not enough
	 * demand is requested downstream.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressurebuffer.png" alt="">
	 *
	 * @return a buffering {@link Stream}
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> onBackpressureBuffer() {
		return new StreamBackpressureBuffer<>(this);
	}

	/**
	 * Request an unbounded demand and push the returned {@link Stream}, or drop the observed elements if not enough
	 * demand is requested downstream.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressuredrop.png" alt="">
	 *
	 * @return a dropping {@link Stream}
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> onBackpressureDrop() {
		return new StreamDrop<>(this);
	}

	/**
	 * Request an unbounded demand and push the returned {@link Stream}, or drop and notify dropping {@link Consumer}
	 * with the observed elements if not enough demand is requested downstream.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressuredropc.png" alt="">
	 *
	 * @return a dropping {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> onBackpressureDrop(Consumer<? super O> onDropped) {
		return new StreamDrop<>(this, onDropped);
	}

	/**
	 * Request an unbounded demand and push the returned
	 * {@link Stream}, or emit onError fom {@link Exceptions#failWithOverflow} if not enough demand is requested
	 * downstream.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressureerror.png" alt="">
	 *
	 * @return an erroring {@link Stream} on backpressure
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
	 * Request an unbounded demand and push the returned {@link Stream}, or only keep the most recent observed item
	 * if not enough demand is requested downstream.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressurelatest.png" alt="">
	 *
	 * @return a dropping {@link Stream} that will only keep a reference to the last observed item
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
	 * Subscribe to a returned fallback publisher when any error occurs.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onerrorreturn.png" alt="">
	 *
	 * @param fallback the error handler for each error
	 *
	 * @return {@link Stream}
	 */
	public final Stream<O> onErrorReturn(final O fallback) {
		return switchOnError(just(fallback));
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Stream} for each unique key evaluated by the given
	 * key mapper. The hashcode of the incoming data will be used for partitionning over
	 * {@link SchedulerGroup#DEFAULT_POOL_SIZE} number of partitions. That
	 * means that at any point of time at most {@link SchedulerGroup#DEFAULT_POOL_SIZE} number of streams will be
	 * created.
	 *
	 * <p> Partition resolution happens accordingly to the positive modulo of the current hashcode over
	 * the
	 * number of
	 * buckets {@link SchedulerGroup#DEFAULT_POOL_SIZE}: <code>bucket = o.hashCode() % buckets;</code>
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/partition.png" alt="">
	 *
	 *
	 * @return a new {@link Stream} whose values are {@link GroupedStream} of all active partionned sequences
	 *
	 * @since 2.0
	 */
	public final Stream<GroupedStream<Integer, O>> partition() {
		return partition(SchedulerGroup.DEFAULT_POOL_SIZE);
	}

	/**
	 *
	 * Re-route incoming values into a dynamically created {@link Stream} for each unique key evaluated by the given
	 * key mapper. The hashcode of the incoming data will be used for partitioning over the buckets number passed. That
	 * means that at any point of time at most {@code buckets} number of streams will be created.
	 *
	 * <p> Partition resolution happens accordingly to the positive modulo of the current hashcode over the
	 * specified number of buckets: <code>bucket = o.hashCode() % buckets;</code>
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/partition.png" alt="">
	 *
	 * @param buckets the maximum number of buckets to partition the values across
	 *
	 * @return a new {@link Stream} whose values are {@link GroupedStream} of all active partionned sequences
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
	 * Create and subscribe a {@link Promise} that will request {@literal 1L} on onSubscribe.
	 * A promise will fulfill at most once. It supports both publish-subscribe with many {@link Subscriber} and
	 * replay to late {@link Subscriber}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/promise.png" alt="">
	 *
	 * @return a subscribed {@link Promise} caching the captured signal.
	 */
	public final Promise<O> promise() {
		return Promise.from(this);
	}

	/**
	 * Prepare a {@link ConnectableStream} which shares this {@link Stream} sequence and dispatches values to 
	 * subscribers in a backpressure-aware manner. Prefetch will default to {@link PlatformDependent#SMALL_BUFFER_SIZE}.
	 * This will effectively turn any type of sequence into a hot sequence.
	 * <p>
	 * Backpressure will be coordinated on {@link Subscription#request} and if any {@link Subscriber} is missing
	 * demand (requested = 0), multicast will pause pushing/pulling.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/publish.png" alt="">
	 * 
	 * @return a new {@link ConnectableStream}
	 */
	public final ConnectableStream<O> publish() {
		return publish(PlatformDependent.SMALL_BUFFER_SIZE);
	}

	/**
	 * Prepare a {@link ConnectableStream} which shares this {@link Stream} sequence and dispatches values to 
	 * subscribers in a backpressure-aware manner. This will effectively turn any type of sequence into a hot sequence.
	 * <p>
	 * Backpressure will be coordinated on {@link Subscription#request} and if any {@link Subscriber} is missing
	 * demand (requested = 0), multicast will pause pushing/pulling.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/publish.png" alt="">
	 * 
	 * @param prefetch bounded requested demand
	 * 
	 * @return a new {@link ConnectableStream}
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
	 * Run subscribe, onSubscribe and request on a supplied {@link ExecutorService}.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/publishon.png" alt="">
	 * <p>
	 * {@code stream.publishOn(ForkJoinPool.commonPool()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param executorService an {@link ExecutorService} to run requests and subscribe on
	 *
	 * @return a {@link Stream} publishing asynchronously
	 */
	public final Stream<O> publishOn(final ExecutorService executorService) {
		return publishOn(new ExecutorServiceScheduler(executorService));
	}

	/**
	 * Aggregate the values from this {@link Stream} sequence into an object of the same type than the
	 * emitted items. The left/right {@link BiFunction} arguments are the N-1 and N item, ignoring sequence
	 * with 0 or 1 element only.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/aggregate.png" alt="">
	 *
	 * @param aggregator the aggregating {@link BiFunction}
	 *
	 * @return a new reduced {@link Stream}
	 *
	 * @since 1.1, 2.0, 2.5
	 */
	public final Mono<O> reduce(final BiFunction<O, O, O> aggregator) {
		if(this instanceof Supplier){
			return MonoSource.wrap(this);
		}
		return new MonoAggregate<>(this, aggregator);
	}

	/**
	 * Accumulate the values from this {@link Stream} sequence into an object matching an initial value type.
	 * The arguments are the N-1 or {@literal initial} value and N current item .
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/reduce.png" alt="">
	 *
	 * @param accumulator the reducing {@link BiFunction}
	 * @param initial the initial left argument to pass to the reducing {@link BiFunction}
	 * @param <A> the type of the initial and reduced object
	 *
	 * @return a new reduced {@link Stream}
	 * @since 1.1, 2.0, 2.5
	 */
	public final <A> Mono<A> reduce(final A initial, BiFunction<A, ? super O, A> accumulator) {

		return reduceWith(new Supplier<A>() {
			@Override
			public A get() {
				return initial;
			}
		}, accumulator);
	}

	/**
	 * Accumulate the values from this {@link Stream} sequence into an object matching an initial value type.
	 * The arguments are the N-1 or {@literal initial} value and N current item .
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/reduce.png" alt="">
	 *
	 * @param accumulator the reducing {@link BiFunction}
	 * @param initial the initial left argument supplied on subscription to the reducing {@link BiFunction}
	 * @param <A> the type of the initial and reduced object
	 *
	 * @return a new reduced {@link Stream}
	 *
	 * @since 1.1, 2.0, 2.5
	 */
	public final <A> Mono<A> reduceWith(final Supplier<A> initial, BiFunction<A, ? super O, A> accumulator) {
		return new MonoReduce<>(this, initial, accumulator);
	}

	/**
	 * Repeatedly subscribe to the source completion of the previous subscription.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/repeat.png" alt="">
	 *
	 * @return an indefinitively repeated {@link Stream} on onComplete
	 *
	 * @since 2.0
	 */
	public final Stream<O> repeat() {
		return repeat(ALWAYS_BOOLEAN_SUPPLIER);
	}

	/**
	 * Repeatedly subscribe to the source if the predicate returns true after completion of the previous subscription.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/repeatb.png" alt="">
	 *
	 * @param predicate the boolean to evaluate on onComplete.
	 *
	 * @return an eventually repeated {@link Stream} on onComplete
	 *
	 * @since 2.5
	 */
	public final Stream<O> repeat(BooleanSupplier predicate) {
		return new StreamRepeatPredicate<>(this, predicate);
	}

	/**
	 * Repeatedly subscribe to the source if the predicate returns true after completion of the previous subscription.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/repeatn.png" alt="">
	 *
	 * @param numRepeat the number of times to re-subscribe on onComplete
	 *
	 * @return an eventually repeated {@link Stream} on onComplete up to number of repeat specified
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> repeat(final long numRepeat) {
		return new StreamRepeat<O>(this, numRepeat);
	}

	/**
	 * Repeatedly subscribe to the source if the predicate returns true after completion of the previous
	 * subscription. A specified maximum of repeat will limit the number of re-subscribe.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/repeatnb.png" alt="">
	 *
	 * @param numRepeat the number of times to re-subscribe on complete
	 * @param predicate the boolean to evaluate on onComplete
	 *
	 * @return an eventually repeated {@link Stream} on onComplete up to number of repeat specified OR matching
	 * predicate
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> repeat(final long numRepeat, BooleanSupplier predicate) {
		return new StreamRepeatPredicate<>(this, countingBooleanSupplier(predicate, numRepeat));
	}

	/**
	 * Repeatedly subscribe to this {@link Stream} when a companion sequence signals a number of emitted elements in
	 * response to the stream completion signal.
	 * <p>If the companion sequence signals when this {@link Stream} is active, the repeat
	 * attempt is suppressed and any terminal signal will terminate this {@link Stream} with the same signal immediately.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/repeatwhen.png" alt="">
	 *
	 * @param whenFactory the {@link Function} providing a {@link Stream} signalling an exclusive number of
	 * emitted elements on onComplete and returning a {@link Publisher} companion.
	 *
	 * @return an eventually repeated {@link Stream} on onComplete when the companion {@link Publisher} produces an
	 * onNext signal
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> repeatWhen(final Function<Stream<Long>, ? extends Publisher<?>> whenFactory) {
		return new StreamRepeatWhen<O>(this, whenFactory);
	}

	/**
	 * Request this {@link Stream} when a companion sequence signals a demand.
	 * <p>If the companion sequence terminates when this
	 * {@link Stream} is active, it  will terminate this {@link Stream} with the same signal immediately.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/requestwhen.png" alt="">
	 *
	 * @param throttleFactory the
	 * {@link Function} providing a {@link Stream} signalling downstream requests and returning a {@link Publisher}
	 * companion that coordinate requests upstream.
	 *
	 * @return a request throttling {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> requestWhen(final Function<? super Stream<? extends Long>, ? extends Publisher<? extends
			Long>> throttleFactory) {
		return new StreamThrottleRequestWhen<O>(this, getTimer(), throttleFactory);
	}

	/**
	 * Re-subscribes to this {@link Stream} sequence if it signals any error
	 * either indefinitely.
	 * <p>
	 * The times == Long.MAX_VALUE is treated as infinite retry.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/retry.png" alt="">
	 *
	 * @return a re-subscribing {@link Stream} on onError
	 *
	 * @since 2.0
	 */
	public final Stream<O> retry() {
		return retry(Long.MAX_VALUE);
	}
	
	/**
	 * Re-subscribes to this {@link Stream} sequence if it signals any error
	 * either indefinitely or a fixed number of times.
	 * <p>
	 * The times == Long.MAX_VALUE is treated as infinite retry.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/retryn.png" alt="">
	 *
	 * @param numRetries the number of times to tolerate an error
	 *
	 * @return a re-subscribing {@link Stream} on onError up to the specified number of retries.
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> retry(long numRetries) {
		return new StreamRetry<O>(this, numRetries);
	}

	/**
	 * Re-subscribes to this {@link Stream} sequence if it signals any error
	 * and the given {@link Predicate} matches otherwise push the error downstream.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/retryb.png" alt="">
	 *
	 * @param retryMatcher the predicate to evaluate if retry should occur based on a given error signal
	 *
	 * @return a re-subscribing {@link Stream} on onError if the predicates matches.
	 *
	 * @since 2.0
	 */
	public final Stream<O> retry(Predicate<Throwable> retryMatcher) {
		return new StreamRetryPredicate<>(this, retryMatcher);
	}

	/**
	 * Re-subscribes to this {@link Stream} sequence up to the specified number of retries if it signals any
	 * error and the given {@link Predicate} matches otherwise push the error downstream.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/retrynb.png" alt="">
	 *
	 * @param numRetries the number of times to tolerate an error
	 * @param retryMatcher the predicate to evaluate if retry should occur based on a given error signal
	 *
	 * @return a re-subscribing {@link Stream} on onError up to the specified number of retries and if the predicate
	 * matches.
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> retry(final long numRetries, final Predicate<Throwable> retryMatcher) {
		return new StreamRetryPredicate<>(this, countingPredicate(retryMatcher, numRetries));
	}

	/**
	 * Retries this {@link Stream} when a companion sequence signals
	 * an item in response to this {@link Stream} error signal
	 * <p>
	 * <p>If the companion sequence signals when the {@link Stream} is active, the retry
	 * attempt is suppressed and any terminal signal will terminate the {@link Stream} source with the same signal
	 * immediately.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/retrywhen.png" alt="">
	 *
	 * @param whenFactory the
	 * {@link Function} providing a {@link Stream} signalling any error from the source sequence and returning a {@link Publisher} companion.
	 *
	 * @return a re-subscribing {@link Stream} on onError when the companion {@link Publisher} produces an
	 * onNext signal
	 *
	 * @since 2.0
	 */
	public final Stream<O> retryWhen(final Function<Stream<Throwable>, ? extends Publisher<?>> whenFactory) {
		return new StreamRetryWhen<O>(this, whenFactory);
	}

	/**
	 * Emit latest value for every given period of ti,e.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/sampletimespan.png" alt="">
	 *
	 * @param timespan the period in second to emit the latest observed item
	 *
	 * @return a sampled {@link Stream} by last item over a period of time
	 */
	public final Stream<O> sample(long timespan) {
		return sample(timespan, TimeUnit.SECONDS);
	}

	/**
	 * Emit latest value for every given period of time.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/sampletimespan.png" alt="">
	 *
	 * @param timespan the period in unit to emit the latest observed item
	 * @param unit the unit of time
	 *
	 * @return a sampled {@link Stream} by last item over a period of time
	 */
	public final Stream<O> sample(long timespan, TimeUnit unit) {
		return sample(interval(timespan, unit));
	}

	/**
	 * Sample this {@link Stream} and emit its latest value whenever the sampler {@link Publisher}
	 * signals a value.
	 * <p>
	 * Termination of either {@link Publisher} will result in termination for the {@link Subscriber}
	 * as well.
	 * <p>
	 * Both {@link Publisher} will run in unbounded mode because the backpressure
	 * would interfere with the sampling precision.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/sample.png" alt="">
	 *
	 * @param sampler the sampler {@link Publisher}
	 *
	 * @return a sampled {@link Stream} by last item observed when the sampler {@link Publisher} signals
	 */
	public final <U> Stream<O> sample(Publisher<U> sampler) {
		return new StreamSample<>(this, sampler);
	}

	/**
	 * Take a value from this {@link Stream} then use the duration provided to skip other values.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/samplefirsttimespan.png" alt="">
	 *
	 * @param timespan the period in seconds to exclude others values from this sequence
	 *
	 * @return a sampled {@link Stream} by first item over a period of time
	 */
	public final Stream<O> sampleFirst(final long timespan) {
		return sampleFirst(timespan, TimeUnit.SECONDS);
	}

	/**
	 * Take a value from this {@link Stream} then use the duration provided to skip other values.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/samplefirsttimespan.png" alt="">
	 *
	 * @param timespan the period in unit to exclude others values from this sequence
	 * @param unit the time unit
	 *
	 * @return a sampled {@link Stream} by first item over a period of time
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
	 * Take a value from this {@link Stream} then use the duration provided by a
	 * generated Publisher to skip other values until that sampler {@link Publisher} signals.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/samplefirst.png" alt="">
	 *
	 * @param samplerFactory select a {@link Publisher} companion to signal onNext or onComplete to stop excluding
	 * others values from this sequence
	 * @param <U> the companion reified type
	 *
	 * @return a sampled {@link Stream} by last item observed when the sampler signals
	 */
	public final <U> Stream<O> sampleFirst(Function<? super O, ? extends Publisher<U>> samplerFactory) {
		return new StreamThrottleFirst<>(this, samplerFactory);
	}


	/**
	 * Emit the last value from this {@link Stream} only if there were no new values emitted
	 * during the time window provided by a publisher for that particular last value.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/sampletimeout.png" alt="">
	 *
	 * @param throttlerFactory select a {@link Publisher} companion to signal onNext or onComplete to stop checking
	 * others values from this sequence and emit the selecting item
	 * @param <U> the companion reified type
	 *
	 * @return a sampled {@link Stream} by last single item observed before a companion {@link Publisher} emits
	 */
	@SuppressWarnings("unchecked")
	public final <U> Stream<O> sampleTimeout(Function<? super O, ? extends Publisher<U>> throttlerFactory) {
		return new StreamThrottleTimeout<>(this, throttlerFactory, SpscLinkedArrayQueue.unboundedSupplier(PlatformDependent
				.XS_BUFFER_SIZE));
	}

	/**
	 * Emit the last value from this {@link Stream} only if there were no newer values emitted
	 * during the time window provided by a publisher for that particular last value. 
	 * <p>The provided {@literal maxConcurrency} will keep a bounded maximum of concurrent timeouts and drop any new 
	 * items until at least one timeout terminates.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/sampletimeoutm.png" alt="">
	 *
	 * @param throttlerFactory select a {@link Publisher} companion to signal onNext or onComplete to stop checking
	 * others values from this sequence and emit the selecting item
	 * @param <U> the throttling type
	 *
	 * @return a sampled {@link Stream} by last single item observed before a companion {@link Publisher} emits
	 */
	@SuppressWarnings("unchecked")
	public final <U> Stream<O> sampleTimeout(Function<? super O, ? extends Publisher<U>> throttlerFactory, long 
			maxConcurrency) {
		if(maxConcurrency == Long.MAX_VALUE){
			return sampleTimeout(throttlerFactory);
		}
		return new StreamThrottleTimeout<>(this, throttlerFactory, QueueSupplier.get(maxConcurrency));
	}

	/**
	 * Accumulate this {@link Stream} values with an accumulator {@link BiFunction} and
	 * returns the intermediate results of this function.
	 * <p>
	 * Unlike {@link #scan(Object, BiFunction)}, this operator doesn't take an initial value
	 * but treats the first {@link Stream} value as initial value.
	 * <br>
	 * The accumulation works as follows:
	 * <pre><code>
	 * result[0] = accumulator(source[0], source[1])
	 * result[1] = accumulator(result[0], source[2])
	 * result[2] = accumulator(result[1], source[3])
	 * ...
	 * </code></pre>
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/accumulate.png" alt="">
	 *
	 * @param accumulator the accumulating {@link BiFunction}
	 *
	 * @return an accumulating {@link Stream}
	 *
	 * @since 1.1, 2.0
	 */
	public final Stream<O> scan(final BiFunction<O, O, O> accumulator) {
		return new StreamAccumulate<>(this, accumulator);
	}

	/**
	 * Aggregate this {@link Stream} values with the help of an accumulator {@link BiFunction}
	 * and emits the intermediate results.
	 * <p>
	 * The accumulation works as follows:
	 * <pre><code>
	 * result[0] = initialValue;
	 * result[1] = accumulator(result[0], source[0])
	 * result[2] = accumulator(result[1], source[1])
	 * result[3] = accumulator(result[2], source[2])
	 * ...
	 * </code></pre>
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/scan.png" alt="">
	 *
	 * @param initial the initial argument to pass to the reduce function
	 * @param accumulator the accumulating {@link BiFunction}
	 * @param <A> the accumulated type
	 *
	 * @return an accumulating {@link Stream} starting with initial state
	 *
	 * @since 1.1, 2.0
	 */
	public final <A> Stream<A> scan(final A initial, final BiFunction<A, ? super O, A> accumulator) {
		return new StreamScan<>(this, initial, accumulator);
	}

	/**
	 * Expect and emit a single item from this {@link Stream} source or signal
	 * {@link java.util.NoSuchElementException} (or a default generated value) for empty source,
	 * {@link IndexOutOfBoundsException} for a multi-item source.
	 * 
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/single.png" alt="">
	 *
	 * @return a {@link Mono} with the eventual single item or an error signal
	 */
	public final Mono<O> single() {
		return new MonoSingle<>(this);
	}

	/**
	 *
	 * Expect and emit a single item from this {@link Stream} source or signal
	 * {@link java.util.NoSuchElementException} (or a default generated value) for empty source,
	 * {@link IndexOutOfBoundsException} for a multi-item source.
	 * 
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/singleordefault.png" alt="">
	 * @param defaultSupplier a {@link Supplier} of a single fallback item if this {@link Stream} is empty
	 *
	 * @return a {@link Mono} with the eventual single item or a supplied default value
	 */
	public final Mono<O> singleOrDefault(Supplier<? extends O> defaultSupplier) {
		return new MonoSingle<>(this, defaultSupplier);
	}

	/**
	 * Expect and emit a zero or single item from this {@link Stream} source or
	 * {@link IndexOutOfBoundsException} for a multi-item source.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/singleorempty.png" alt="">
	 *
	 * @return a {@link Mono} with the eventual single item or no item
	 */
	public final Mono<O> singleOrEmpty() {
		return new MonoSingle<>(this, MonoSingle.<O>completeOnEmptySequence());
	}

	/**
	 * Skip next the specified number of elements from this {@link Stream}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/skip.png" alt="">
	 *
	 * @param skipped the number of times to drop
	 *
	 * @return a dropping {@link Stream} until the specified skipped number of elements
	 *
	 * @since 2.0
	 */
	public final Stream<O> skip(long skipped) {
		if (skipped > 0) {
			return new StreamSkip<>(this, skipped);
		}
		else {
			return this;
		}
	}

	/**
	 * Skip elements from this {@link Stream} for the given time period.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/skiptime.png" alt="">
	 *
	 * @param timespan the time window to exclude next signals
	 * @param unit the time unit to use
	 *
	 * @return a dropping {@link Stream} until the end of the given timespan
	 *
	 * @since 2.0
	 */
	public final Stream<O> skip(long timespan, TimeUnit unit) {
		if(timespan > 0) {
			Timer timer = getTimer();
			Assert.isTrue(timer != null, "Timer can't be found, try assigning an environment to the stream");
			return skipUntil(Mono.delay(timespan, unit, timer));
		}
		else{
			return this;
		}
	}

	/**
	 * Skip the last specified number of elements from this {@link Stream}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/skiplast.png" alt="">
	 *
	 * @param n the number of elements to ignore before completion
	 *
	 * @return a dropping {@link Stream} for the specified skipped number of elements before termination
	 *
	 * @since 2.5
	 */
	public final Stream<O> skipLast(int n) {
		return new StreamSkipLast<>(this, n);
	}

	/**
	 * Skip values from this {@link Stream} until a specified {@link Publisher} signals
	 * an onNext or onComplete.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/skipuntil.png" alt="">
	 *
	 * @param other the {@link Publisher} companion to coordinate with to stop skipping
	 *
	 * @return a dropping {@link Stream} until the other {@link Publisher} emits
	 *
	 * @since 2.5
	 */
	public final Stream<O> skipUntil(final Publisher<?> other) {
		return new StreamSkipUntil<>(this, other);
	}

	/**
	 * Skips values from this {@link Stream} while a {@link Predicate} returns true for the value.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/skipwhile.png" alt="">
	 *
	 * @param skipPredicate the {@link Predicate} evaluating to true to keep skipping.
	 *
	 * @return a dropping {@link Stream} while the {@link Predicate} matches
	 *
	 * @since 2.0
	 */
	public final Stream<O> skipWhile(final Predicate<? super O> skipPredicate) {
		return new StreamSkipWhile<>(this, skipPredicate);
	}

	/**
	 * Prepend the given {@link Iterable} before this {@link Stream} sequence.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/startwithi.png" alt="">
	 *
	 * @return a prefixed {@link Stream} with given {@link Iterable}
	 *
	 * @since 2.0
	 */
	public final Stream<O> startWith(final Iterable<O> iterable) {
		return startWith(fromIterable(iterable));
	}

	/**
	 * Prepend the given values before this {@link Stream} sequence.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/startwithv.png" alt="">
	 *
	 * @return a prefixed {@link Stream} with given values
	 *
	 * @since 2.0
	 */
	@SafeVarargs
	@SuppressWarnings("varargs")
	public final Stream<O> startWith(final O... values) {
		return startWith(just(values));
	}

	/**
	 * Prepend the given {@link Publisher} sequence before this {@link Stream} sequence.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/startwith.png" alt="">
	 *
	 * @return a prefixed {@link Stream} with given {@link Publisher} sequence
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
	 *
	 * @param alternate the alternate publisher if this sequence is empty
	 *
	 * @return an alternating {@link Stream} on source onComplete without elements
	 *
	 * @since 2.5
	 */
	public final Stream<O> switchIfEmpty(final Publisher<? extends O> alternate) {
		return new StreamSwitchIfEmpty<>(this, alternate);
	}

	/**
	 * Switch to a new {@link Publisher} generated via a {@link Function} whenever this {@link Stream} produces an item.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/switchmap.png" alt="">
	 *
	 * @param fn the transformation function
	 * @param <V> the type of the return value of the transformation function
	 *
	 * @return an alternating {@link Stream} on source onNext
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
	 *
	 * @param fallback the alternate {@link Publisher}
	 *
	 * @return an alternating {@link Stream} on source onError
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
	 * Take only the first N values from this {@link Stream}.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/take.png" alt="">
	 * <p>
	 * If N is zero, the {@link Subscriber} gets completed if this {@link Stream} completes, signals an error or
	 * signals its first value (which is not not relayed though).
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/take0.png" alt="">
	 * @param n the number of items to emit from this {@link Stream}
	 *
	 * @return a size limited {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> take(final long n) {
		return new StreamTake<O>(this, n);
	}

	/**
	 * Relay values from this {@link Stream} until the given time period elapses.
	 * <p>
	 * If the time period is zero, the {@link Subscriber} gets completed if this {@link Stream} completes, signals an
	 * error or
	 * signals its first value (which is not not relayed though).
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/taketime.png" alt="">
	 *
	 * @param timespan the time window of items to emit from this {@link Stream}
	 * @param unit the time unit to use
	 *
	 * @return a time limited {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> take(long timespan, TimeUnit unit) {
		if (timespan > 0) {
			Timer timer = getTimer();
			Assert.isTrue(timer != null, "Timer can't be found, try assigning an environment to the stream");
			return takeUntil(Mono.delay(timespan, unit, timer));
		}
		else if(timespan == 0) {
			return take(0);
		}
		throw new IllegalArgumentException("timespan >= 0 required but it was " + timespan);
	}

	/**
	 * Emit the last N values this {@link Stream} emitted before its completion.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/takelast.png" alt="">
	 *
	 * @param n the number of items from this {@link Stream} to retain and emit on onComplete
	 *
	 * @return a terminating {@link Stream} sub-sequence
	 *
	 * @since 2.5
	 */
	public final Stream<O> takeLast(int n) {
		return new StreamTakeLast<>(this, n);
	}


	/**
	 * Relay values until a predicate returns {@literal TRUE}, indicating the sequence should stop
	 * (checked after each value has been delivered).
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/takeuntilp.png" alt="">
	 *
	 * @param stopPredicate the {@link Predicate} invoked each onNext returning {@literal TRUE} to terminate
	 *
	 * @return an eventually limited {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> takeUntil(final Predicate<? super O> stopPredicate) {
		return new StreamTakeUntilPredicate<>(this, stopPredicate);
	}

	/**
	 * Relay values from this {@link Stream} until the given {@link Publisher} emits.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/takeuntil.png" alt="">
	 *
	 * @param other the {@link Publisher} to signal when to stop replaying signal from this {@link Stream}
	 *
	 * @return an eventually limited {@link Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> takeUntil(final Publisher<?> other) {
		return new StreamTakeUntil<>(this, other);
	}

	/**
	 * Relay values while a predicate returns
	 * {@literal FALSE} for the values (checked before each value is delivered).
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/takewhile.png" alt="">
	 *
	 * @param continuePredicate the {@link Predicate} invoked each onNext returning {@literal FALSE} to terminate
	 *
	 * @return an eventually limited {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> takeWhile(final Predicate<? super O> continuePredicate) {
		return new StreamTakeWhile<O>(this, continuePredicate);
	}

	/**
	 * Create a {@link StreamTap} that maintains a reference to the last value seen by this {@link Stream}. The {@link StreamTap} is
	 * continually updated when new values pass through the {@link Stream}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tap.png" alt="">
	 *
	 * @return a peekable {@link StreamTap}
	 */
	public final StreamTap<O> tap() {
		return StreamTap.tap(this);
	}

	/**
	 * @see #sampleFirst(Function)
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/throttlefirst.png" alt="">
	 *
	 * @return a sampled {@link Stream} by last item observed when the sampler signals
	 *
	 * @since 2.5
	 */
	public final <U> Stream<O> throttleFirst(Function<? super O, ? extends Publisher<U>> throttler) {
		return sampleFirst(throttler);
	}

	/**
	 * @see #sample(Publisher)
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/throttlelast.png" alt="">
	 *
	 * @return a sampled {@link Stream} by last item observed when the sampler {@link Publisher} signals
	 *
	 * @since 2.5
	 */
	public final <U> Stream<O> throttleLast(Publisher<U> throttler) {
		return sample(throttler);
	}

	/**
	 *
	 * Relay requests of N into N delayed requests of 1 to this {@link Stream}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/throttlerequest.png" alt="">
	 *
	 * @param period the period in milliseconds to delay downstream requests of N into N x delayed requests of 1
	 *
	 * @return a timed step-request {@link Stream}
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
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/throttletimeout.png" alt="">
	 *
	 * @return a sampled {@link Stream} by last single item observed before a companion {@link Publisher} emits
	 *
	 * @since 2.5
	 */
	public final <U> Stream<O> throttleTimeout(Function<? super O, ? extends Publisher<U>> throttler) {
		return sampleTimeout(throttler);
	}

	/**
	 * Signal a {@link java.util.concurrent.TimeoutException} error in case a per-item period in milliseconds fires
	 * before the next item arrives from this {@link Stream}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timeouttime.png" alt="">
	 *
	 * @param timeout the timeout in milliseconds between two signals from this {@link Stream}
	 *
	 * @return a per-item expirable {@link Stream}
	 *
	 * @since 1.1, 2.0
	 */
	public final Stream<O> timeout(long timeout) {
		return timeout(timeout, null);
	}

	/**
	 * Signal a {@link java.util.concurrent.TimeoutException} in case a per-item period fires before the
	 * next item arrives from this {@link Stream}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timeouttime.png" alt="">
	 *
	 * @param timeout the timeout in milliseconds between two signals from this {@link Stream}
	 * @param unit the time unit
	 *
	 * @return a per-item expirable {@link Stream}
	 *
	 * @since 1.1, 2.0
	 */
	public final Stream<O> timeout(long timeout, TimeUnit unit) {
		return timeout(timeout, unit, null);
	}

	/**
	 * Switch to a fallback {@link Publisher} in case a per-item period
	 * fires before the next item arrives from this {@link Stream}.
	 *
	 * <p> If the given {@link Publisher} is null, signal a {@link java.util.concurrent.TimeoutException}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timeouttimefallback.png" alt="">
	 *
	 * @param timeout the timeout in milliseconds between two signals from this {@link Stream}
	 * @param unit the time unit
	 * @param fallback the fallback {@link Publisher} to subscribe when a timeout occurs
	 *
	 * @return a per-item expirable {@link Stream} with a fallback {@link Publisher}
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

		if(fallback == null) {
			return timeout(_timer, rest);
		}
		return timeout(_timer, rest, fallback);
	}


	/**
	 * Signal a {@link java.util.concurrent.TimeoutException} in case a first item from this {@link Stream} has
	 * not been emitted before the given {@link Publisher} emits.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timeoutfirst.png" alt="">
	 *
	 * @param firstTimeout the timeout {@link Publisher} that must not emit before the first signal from this {@link Stream}
	 *
	 * @return an expirable {@link Stream} if the first item does not come before a {@link Publisher} signal
	 *
	 * @since 2.5
	 */
	public final <U> Stream<O> timeout(final Publisher<U> firstTimeout) {
		return timeout(firstTimeout, new Function<O, Publisher<U>>() {
			@Override
			public Publisher<U> apply(O o) {
				return never();
			}
		});
	}

	/**
	 * Signal a {@link java.util.concurrent.TimeoutException} in case a first item from this {@link Stream} has
	 * not been emitted before the given {@link Publisher} emits. The following items will be individually timed via
	 * the factory provided {@link Publisher}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timeoutall.png" alt="">
	 *
	 * @param firstTimeout the timeout {@link Publisher} that must not emit before the first signal from this {@link Stream}
	 * @param nextTimeoutFactory the timeout {@link Publisher} factory for each next item
	 *
	 * @return a first then per-item expirable {@link Stream}
	 *
	 * @since 2.5
	 */
	public final <U, V> Stream<O> timeout(Publisher<U> firstTimeout,
			Function<? super O, ? extends Publisher<V>> nextTimeoutFactory) {
		return new StreamTimeout<>(this, firstTimeout, nextTimeoutFactory);
	}

	/**
	 * Switch to a fallback {@link Publisher} in case a first item from this {@link Stream} has
	 * not been emitted before the given {@link Publisher} emits. The following items will be individually timed via
	 * the factory provided {@link Publisher}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timeoutallfallback.png" alt="">
	 *
	 * @param firstTimeout the timeout {@link Publisher} that must not emit before the first signal from this {@link Stream}
	 * @param nextTimeoutFactory the timeout {@link Publisher} factory for each next item
	 * @param fallback the fallback {@link Publisher} to subscribe when a timeout occurs
	 *
	 * @return a first then per-item expirable {@link Stream} with a fallback {@link Publisher}
	 *
	 * @since 2.5
	 */
	public final <U, V> Stream<O> timeout(Publisher<U> firstTimeout,
			Function<? super O, ? extends Publisher<V>> nextTimeoutFactory, final Publisher<? extends O>
			fallback) {
		return new StreamTimeout<>(this, firstTimeout, nextTimeoutFactory, fallback);
	}

	/**
	 * Emit a {@link reactor.fn.tuple.Tuple2} pair of T1 {@link Long} current system time in
	 * millis and T2 {@link <T>} associated data for each item from this {@link Stream}
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timestamp.png" alt="">
	 *
	 * @return a timestamped {@link Stream}
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
	 * Transform this {@link Stream} into a lazy {@link Iterable#iterator()} blocking on next calls using a prefetch
	 * size of 1L.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/toiterablen.png" alt="">
	 *
	 * @return a blocking {@link Iterator}
	 */
	public final Iterator<O> toIterator() {
		return toIterable(1L).iterator();
	}

	/**
	 * Accumulate this {@link Stream} sequence in a {@link List} that is emitted to the returned {@link Mono} on
	 * onComplete.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tolist.png" alt="">
	 *
	 * @return a {@link Mono} of all values from this {@link Stream}
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
	 * Convert all this
	 * {@link Stream} sequence into a hashed map where the key is extracted by the given {@link Function} and the
	 * value will be the most recent emitted item for this key.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tomap.png" alt="">
	 *
	 * @param keyExtractor a {@link Function} to route items into a keyed {@link Collection}
	 *
	 * @return a {@link Mono} of all last matched key-values from this {@link Stream}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public final <K> Mono<Map<K, O>> toMap(Function<? super O, ? extends K> keyExtractor) {
		return toMap(keyExtractor, (Function<O, O>)IDENTITY_FUNCTION);
	}

	/**
	 * Convert all this {@link Stream} sequence into a hashed map where the key is extracted by the given function and the value will be
	 * the most recent extracted item for this key.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tomap.png" alt="">
	 *
	 * @param keyExtractor a {@link Function} to route items into a keyed {@link Collection}
	 * @param valueExtractor a {@link Function} to select the data to store from each item
	 *
	 * @return a {@link Mono} of all last matched key-values from this {@link Stream}
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
	 * Convert all this {@link Stream} sequence into a supplied map where the key is extracted by the given function and the value will
	 * be the most recent extracted item for this key.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tomap.png" alt="">
	 *
	 * @param keyExtractor a {@link Function} to route items into a keyed {@link Collection}
	 * @param valueExtractor a {@link Function} to select the data to store from each item
	 * @param mapSupplier a {@link Map} factory called for each {@link Subscriber}
	 *
	 * @return a {@link Mono} of all last matched key-values from this {@link Stream}
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
	 * Convert this {@link Stream} sequence into a hashed map where the key is extracted by the given function and the value will be
	 * all the emitted item for this key.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tomultimap.png" alt="">
	 *
	 * @param keyExtractor a {@link Function} to route items into a keyed {@link Collection}
	 *
	 * @return a {@link Mono} of all matched key-values from this {@link Stream}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public final <K> Mono<Map<K, Collection<O>>> toMultimap(Function<? super O, ? extends K> keyExtractor) {
		return toMultimap(keyExtractor, (Function<O, O>)IDENTITY_FUNCTION);
	}

	/**
	 * Convert this {@link Stream} sequence into a hashed map where the key is extracted by the given function and the value will be
	 * all the extracted items for this key.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tomultimap.png" alt="">
	 *
	 * @param keyExtractor a {@link Function} to route items into a keyed {@link Collection}
	 * @param valueExtractor a {@link Function} to select the data to store from each item
	 *
	 * @return a {@link Mono} of all matched key-values from this {@link Stream}
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
	 * Convert this {@link Stream} sequence into a supplied map where the key is extracted by the given function and the value will
	 * be all the extracted items for this key.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tomultimap.png" alt="">
	 *
	 * @param keyExtractor a {@link Function} to route items into a keyed {@link Collection}
	 * @param valueExtractor a {@link Function} to select the data to store from each item
	 * @param mapSupplier a {@link Map} factory called for each {@link Subscriber}
	 *
	 * @return a {@link Mono} of all matched key-values from this {@link Stream}
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
	 * Configure a hinted capacity {@literal Long.MAX_VALUE} that can be used by downstream operators to adapt a
	 * better consuming strage.
	 *
	 * @return {@link Stream} with capacity set to max
	 *
	 * @see #capacity(long)
	 */
	public final Stream<O> useNoCapacity() {
		return capacity(Long.MAX_VALUE);
	}

	/**
	 * Configure a {@link Timer} that can be used by timed operators downstream.
	 *
	 * @param timer the timer
	 *
	 * @return a configured stream
	 */
	public Stream<O> useTimer(final Timer timer) {
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
	 * Re-route incoming values into a dynamically created {@link Stream} every pre-defined {@param backlog} times. The
	 * nested streams will be pushed into the returned {@link Stream}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsize.png" alt="">
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
	 * Route incoming values into multiple {@link Stream} delimited by the given {@code skip} count and starting from
	 * the first item of each batch.
	 * Each {@link Stream} bucket will onComplete after {@code maxSize} items have been routed.
	 *
	 * <p>
	 * When skip > maxSize : dropping windows
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsizeskip.png" alt="">
	 * <p>
	 * When maxSize < skip : overlapping windows
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsizeskipover.png" alt="">
	 * <p>
	 * When skip == maxSize : exact windows
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsize.png" alt="">
	 *
	 * @param maxSize the maximum re-routed items per {@link Stream}
	 * @param skip the number of items to count before emitting a new bucket {@link Stream}
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
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowboundary.png" alt="">
	 *
	 * @param boundarySupplier the the stream to listen to for separating each window
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 */
	public final Stream<Stream<O>> window(final Publisher<?> boundarySupplier) {
		return new StreamWindowBoundary<>(this,
				boundarySupplier,
				SpscLinkedArrayQueue.<O>unboundedSupplier(PlatformDependent.XS_BUFFER_SIZE),
				SpscLinkedArrayQueue.unboundedSupplier(PlatformDependent.XS_BUFFER_SIZE));
	}

	/**
	 * Re-route incoming values into bucket streams that will be pushed into the returned {@link Stream} every and
	 * complete every time {@code boundarySupplier} stream emits an item. Window starts forwarding when the
	 * bucketOpening stream emits an item, then subscribe to the boundary supplied to complete.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowopenclose.png" alt="">
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
				SpscLinkedArrayQueue.unboundedSupplier(PlatformDependent.XS_BUFFER_SIZE),
				SpscLinkedArrayQueue.<O>unboundedSupplier(PlatformDependent.XS_BUFFER_SIZE));
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Stream} every pre-defined timespan. The nested streams
	 * will be pushed into the returned {@link Stream}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowtimespan.png" alt="">
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
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsizetimeout.png" alt="">
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
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowtimeshiftover.png" alt="">
	 *
	 * @param timespan the period in unit to use to complete a window
	 * @param timeshift the period in unit to use to create a new window
	 * @param unit the time unittime
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
	 * Combine values from this {@link Stream} with values from another
	 * {@link Publisher} through a {@link BiFunction} and emits the result.
	 * <p>
	 * The operator will drop values from this {@link Stream} until the other
	 * {@link Publisher} produces any value.
	 * <p>
	 * If the other {@link Publisher} completes without any value, the sequence is completed.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/withlatestfrom.png" alt="">
	 *
	 * @param other the {@link Publisher} to combine with
	 * @param <U> the other {@link Publisher} sequence type
	 *
	 * @return a combined {@link Stream} gated by another {@link Publisher}
	 */
	public final <U, R> Stream<R> withLatestFrom(Publisher<? extends U> other, BiFunction<? super O, ? super U, ?
			extends R > resultSelector){
		return new StreamWithLatestFrom<>(this, other, resultSelector);
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
	 * Pairwise combines as {@link Tuple2} elements of this {@link Stream} and an {@link Iterable} sequence.
	 *
	 * @param iterable the {@link Iterable} to pair with
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipwithiterable.png" alt="">
	 *
	 * @return a zipped {@link Stream}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public final <T2> Stream<Tuple2<O, T2>> zipWithIterable(Iterable<? extends T2> iterable) {
		return new StreamZipIterable<>(this, iterable, (BiFunction<O, T2, Tuple2<O, T2>>)TUPLE2_BIFUNCTION);
	}

	/**
	 * Pairwise combines elements of this
	 * {@link Stream} and an {@link Iterable} sequence using the given zipper {@link BiFunction}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipwithiterable.png" alt="">
	 *
	 * @param iterable the {@link Iterable} to pair with
	 * @param zipper the {@link BiFunction} combinator
	 *
	 * @return a zipped {@link Stream}
	 *
	 * @since 2.5
	 */
	public final <T2, V> Stream<V> zipWithIterable(Iterable<? extends T2> iterable,
			BiFunction<? super O, ? super T2, ? extends V> zipper) {
		return new StreamZipIterable<>(this, iterable, zipper);
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
		if (max <= 0) {
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