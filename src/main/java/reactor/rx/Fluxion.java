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

import java.time.Duration;
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
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Stream;

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
import reactor.core.tuple.Tuple;
import reactor.core.tuple.Tuple2;
import reactor.core.tuple.Tuple3;
import reactor.core.tuple.Tuple4;
import reactor.core.tuple.Tuple5;
import reactor.core.tuple.Tuple6;
import reactor.core.tuple.Tuple7;
import reactor.core.tuple.Tuple8;
import reactor.core.util.Assert;
import reactor.core.util.EmptySubscription;
import reactor.core.util.Exceptions;
import reactor.core.util.Logger;
import reactor.core.util.PlatformDependent;
import reactor.core.util.ReactiveStateUtils;
import reactor.core.util.WaitStrategy;
import reactor.rx.subscriber.InterruptableSubscriber;
import reactor.rx.subscriber.ManualSubscriber;

/**
 * A Reactive Stream {@link Publisher} implementing a complete scope of Reactive Extensions.
 * <p>
 * A {@link Fluxion} is a sequence of 0..N events flowing via callbacks to {@link Subscriber#onNext(Object)}.
 * Static source generators are available and allow for transfromation from functional callbacks or plain Java types
 * like {@link Iterable} or {@link Future}.
 * Instance methods will build new templates of {@link Fluxion} also called operators. Their role is to build a
 * delegate
 * chain of
 * {@link Subscriber} materialized only when {@link Fluxion#subscribe}
 * is called. Materialization will operate by propagating a
 * {@link Subscription} from the root {@link Publisher} via {@link Subscriber#onSubscribe(Subscription)}.
 *
 *
 * This API is not directly an extension of
 * {@link Flux} but considerably expands its scope. and conversions between {@link Fluxion} and {@link Flux} can
 * be achieved using the operator {@link #as} : {@code flux.as(Fluxion::from)}.
 * <p>
 * <img width="640" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/fluxion.png" alt="">
 * <pre>
 * {@code
 * Fluxion.just(1, 2, 3).map(i -> i*2) //...
 *
 * Broadcaster<String> fluxion = Broadcaster.create()
 * fluxion.map(i -> i*2).consume(System.out::println);
 * fluxion.onNext("hello");
 *
 * Fluxion.range(1, 1_000_000)
 *  .publishOn(SchedulerGroup.io())
 *  .timestamp()
 *  .consume(System.out::println);
 *
 * Fluxion.interval(1)
 *  .map(i -> "left")
 *  .mergeWith(Fluxion.interval(2).map(i -> "right"))
 *  .consume(System.out::println);
 * 
 * Iterable<T> iterable =
 *  Flux.fromIterable(iterable)
 *  .as(Fluxion::from)
 *  .onBackpressureDrop()
 *  .toList()
 *  .get();
 * }
 * </pre>
 *
 * @param <O> The type of the output values
 *
 * @author Stephane Maldini
 * @author Sebastien Deleuze
 * @since 1.1, 2.0, 2.5
 */
public abstract class Fluxion<O> implements Publisher<O>, Backpressurable, Introspectable {


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
	 * @return a new {@link Fluxion} eventually subscribed to one of the sources or empty
	 */
	public static <T> Fluxion<T> amb(Iterable<? extends Publisher<? extends T>> sources) {
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
	 * @return a new {@link Fluxion} eventually subscribed to one of the sources or empty
	 */
	@SuppressWarnings("varargs")
	@SafeVarargs
	public static <T> Fluxion<T> amb(Publisher<? extends T>... sources) {
		return from(Flux.amb(sources));
	}


	/**
	 * Build a {@link Fluxion} whose data are generated by the combination of the most recent published values from
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
	 * @return a {@link Fluxion} based on the produced combinations
	 * @since 2.0, 2.5
	 */
	@SuppressWarnings({"unchecked", "varargs"})
	@SafeVarargs
	public static <T, V> Fluxion<V> combineLatest(final Function<Object[], V> combinator,
			Publisher<? extends T>... sources) {
		if (sources == null || sources.length == 0) {
			return empty();
		}

		if (sources.length == 1) {
			return from((Publisher<V>) sources[0]);
		}

		return new FluxionCombineLatest<>(sources,
				combinator,
				QueueSupplier.<FluxionCombineLatest.SourceAndArray>xs(),
				PlatformDependent.XS_BUFFER_SIZE);
	}

	/**
	 * Build a {@link Fluxion} whose data are generated by the combination of the most recent published values from
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
	 * @return a {@link Fluxion} based on the produced value
	 *
	 * @since 2.5
	 */
	public static <T1, T2, V> Fluxion<V> combineLatest(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			BiFunction<? super T1, ? super T2, ? extends V> combinator) {
		return new FluxionWithLatestFrom<>(source1, source2, combinator);
	}

	/**
	 * Build a {@link Fluxion} whose data are generated by the combination of the most recent published values from
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
	 * @return a {@link Fluxion} based on the produced value
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, V> Fluxion<V> combineLatest(Publisher<? extends T1> source1,
	                                                      Publisher<? extends T2> source2,
	                                                      Publisher<? extends T3> source3,
			Function<Object[], V> combinator) {
		return combineLatest(combinator, source1, source2, source3);
	}

	/**
	 * Build a {@link Fluxion} whose data are generated by the combination of the most recent published values from
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
	 * @return a {@link Fluxion} based on the produced value
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4, V> Fluxion<V> combineLatest(Publisher<? extends T1> source1,
	                                                          Publisher<? extends T2> source2,
	                                                          Publisher<? extends T3> source3,
	                                                          Publisher<? extends T4> source4,
			Function<Object[], V> combinator) {
		return combineLatest(combinator, source1, source2, source3, source4);
	}

	/**
	 * Build a {@link Fluxion} whose data are generated by the combination of the most recent published values from
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
	 * @return a {@link Fluxion} based on the produced value
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4, T5, V> Fluxion<V> combineLatest(Publisher<? extends T1> source1,
	                                                              Publisher<? extends T2> source2,
	                                                              Publisher<? extends T3> source3,
	                                                              Publisher<? extends T4> source4,
	                                                              Publisher<? extends T5> source5,
			Function<Object[], V> combinator) {
		return combineLatest(combinator, source1, source2, source3, source4, source5);
	}

	/**
	 * Build a {@link Fluxion} whose data are generated by the combination of the most recent published values from
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
	 * @return a {@link Fluxion} based on the produced value
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4, T5, T6, V> Fluxion<V> combineLatest(Publisher<? extends T1> source1,
	                                                                  Publisher<? extends T2> source2,
	                                                                  Publisher<? extends T3> source3,
	                                                                  Publisher<? extends T4> source4,
	                                                                  Publisher<? extends T5> source5,
	                                                                  Publisher<? extends T6> source6,
			Function<Object[], V> combinator) {
		return combineLatest(combinator, source1, source2, source3, source4, source5, source6);
	}

	/**
	 * Build a {@link Fluxion} whose data are generated by the combination of the most recent published values from
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
	 * @return a {@link Fluxion} based on the produced value
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4, T5, T6, T7, V> Fluxion<V> combineLatest(Publisher<? extends T1> source1,
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
	 * Build a {@link Fluxion} whose data are generated by the combination of the most recent published values from
	 * all publishers.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png" alt="">
	 *
	 * @param sources    The list of upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <V>        The produced output after transformation by the given combinator
	 * @return a {@link Fluxion} based on the produced value
	 * @since 2.0, 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <T, V> Fluxion<V> combineLatest(Iterable<? extends Publisher<? extends T>> sources,
			final Function<Object[], V> combinator) {
		if (sources == null) {
			return empty();
		}

		return new FluxionCombineLatest<>(sources,
				combinator, QueueSupplier.<FluxionCombineLatest.SourceAndArray>xs(),
				PlatformDependent.XS_BUFFER_SIZE
		);
	}

	/**
	 * Build a {@link Fluxion} whose data are generated by the combination of the most recent published values from
	 * all publishers.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png" alt="">
	 *
	 * @param sources    The publisher of upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <V>        The produced output after transformation by the given combinator
	 * @return a {@link Fluxion} based on the produced value
	 * @since 2.0, 2.5
	 */
	public static <V> Fluxion<V> combineLatest(Publisher<? extends Publisher<?>> sources,
			final Function<Object[], V> combinator) {
		return from(sources).buffer()
		                    .flatMap(new Function<List<? extends Publisher<?>>, Publisher<V>>() {
					@Override
					public Publisher<V> apply(List<? extends Publisher<?>> publishers) {
						return new FluxionCombineLatest<>(publishers,
								combinator,
								QueueSupplier.<FluxionCombineLatest.SourceAndArray>xs(),
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
	 *
	 * @param sources The {@link Publisher} of {@link Publisher} to concat
	 * @param <T> The source type of the data sequence
	 *
	 * @return a new {@link Fluxion} concatenating all source sequences
	 */
	public static <T> Fluxion<T> concat(Iterable<? extends Publisher<? extends T>> sources) {
		return new FluxionConcatIterable<>(sources);
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
	 * @return a new {@link Fluxion} concatenating all inner sources sequences until complete or error
	 */
	public static <T> Fluxion<T> concat(Publisher<? extends Publisher<? extends T>> sources) {
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
	 * @return a new {@link Fluxion} concatenating all source sequences
	 */
	@SuppressWarnings("varargs")
	@SafeVarargs
	public static <T> Fluxion<T> concat(Publisher<? extends T>... sources) {
		return new FluxionConcatArray<>(sources);
	}

	/**
	 * Try to convert an object to a {@link Fluxion} using available support from reactor-core given the following
	 * ordering  :
	 * <ul>
	 *     <li>null to {@link #empty()}</li>
	 *     <li>Publisher to Fluxion</li>
	 *     <li>Iterable to Fluxion</li>
	 *     <li>Iterator to Fluxion</li>
	 *     <li>RxJava 1 Single to Fluxion</li>
	 *     <li>RxJava 1 Observable to Fluxion</li>
	 *     <li>JDK 9 Flow.Publisher to Fluxion</li>
	 * </ul>
	 *
	 * @param source an object emitter to convert to a {@link Publisher}
	 * @param <T> a parameter candidate generic for the returned {@link Fluxion}
	 *
	 * @return a new parameterized (unchecked) converted {@link Fluxion}
	 */
	@SuppressWarnings("unchecked")
	public static <T> Fluxion<T> convert(Object source) {
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
			return (Fluxion<T>) from(DependencyUtils.convertToPublisher(source));
		}
	}

	/**
	 * Create a {@link Fluxion} reacting on each available {@link Subscriber} read derived with the passed {@link
	 * Consumer}. If a previous request is still running, avoid recursion and extend the previous request iterations.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/generateforeach.png" alt="">
	 * <p>
	 * @param requestConsumer A {@link Consumer} invoked when available read with the target subscriber
	 * @param <T> The type of the data sequence
	 *
	 * @return a new {@link Fluxion}
	 */
	public static <T> Fluxion<T> create(Consumer<SubscriberWithContext<T, Void>> requestConsumer) {
		return from(Flux.create(requestConsumer));
	}

	/**
	 * Create a {@link Fluxion} reacting on each available {@link Subscriber} read derived with the passed {@link
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
	 * @return a new {@link Fluxion}
	 */
	public static <T, C> Fluxion<T> create(Consumer<SubscriberWithContext<T, C>> requestConsumer,
			Function<Subscriber<? super T>, C> contextFactory) {
		return from(Flux.create(requestConsumer, contextFactory));
	}

	/**
	 * Create a {@link Fluxion} reacting on each available {@link Subscriber} read derived with the passed {@link
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
	 * @return a new {@link Fluxion}
	 */
	public static <T, C> Fluxion<T> create(Consumer<SubscriberWithContext<T, C>> requestConsumer,
			Function<Subscriber<? super T>, C> contextFactory,
			Consumer<C> shutdownConsumer) {
		return from(Flux.create(requestConsumer, contextFactory, shutdownConsumer));
	}

	/**
	 * Create a {@link Fluxion} reacting on requests with the passed {@link BiConsumer}
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/generate.png" alt="">
	 *
	 * @param requestConsumer A {@link BiConsumer} with left argument request and right argument target subscriber
	 * @param <T>             The type of the data sequence
	 * @return a Stream
	 * @since 2.0.2
	 */
	public static <T> Fluxion<T> generate(BiConsumer<Long, SubscriberWithContext<T, Void>> requestConsumer) {
		if (requestConsumer == null) throw new IllegalArgumentException("Supplier must be provided");
		return generate(requestConsumer, null, null);
	}

	/**
	 * Create a {@link Fluxion} reacting on requests with the passed {@link BiConsumer}
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
	public static <T, C> Fluxion<T> generate(BiConsumer<Long, SubscriberWithContext<T, C>> requestConsumer,
	                                          Function<Subscriber<? super T>, C> contextFactory) {
		return generate(requestConsumer, contextFactory, null);
	}

	/**
	 * Create a {@link Fluxion} reacting on requests with the passed {@link BiConsumer}. The argument {@code
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
	 * @return a fresh Reactive {@link Fluxion} publisher ready to be subscribed
	 *
	 * @since 2.0.2
	 */
	public static <T, C> Fluxion<T> generate(BiConsumer<Long, SubscriberWithContext<T, C>> requestConsumer,
	                                          Function<Subscriber<? super T>, C> contextFactory,
	                                          Consumer<C> shutdownConsumer) {
		return from(Flux.generate(requestConsumer, contextFactory, shutdownConsumer));
	}

	/**
	 * Supply a {@link Publisher} everytime subscribe is called on the returned fluxion. The passed {@link Supplier}
	 * will be invoked and it's up to the developer to choose to return a new instance of a {@link Publisher} or reuse
	 * one effecitvely behaving like {@link #from(Publisher)}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/defer.png" alt="">
	 *
	 * @param supplier the {@link Publisher} {@link Supplier} to call on subscribe
	 * @param <T>      the type of values passing through the {@link Fluxion}
	 *
	 * @return a deferred {@link Fluxion}
	 */
	public static <T> Fluxion<T> defer(Supplier<? extends Publisher<T>> supplier) {
		return new FluxionDefer<>(supplier);
	}

	/**
	 * Create a {@link Fluxion} that onSubscribe and immediately onComplete without emitting any item.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/empty.png" alt="">
	 * <p>
	 * @param <T> the reified type of the target {@link Subscriber}
	 *
	 * @return an empty {@link Fluxion}
	 */
	@SuppressWarnings("unchecked")
	public static <T> Fluxion<T> empty() {
		return (Fluxion<T>) FluxionJust.EMPTY;
	}

	/**
	 * Create a {@link Fluxion} that onSubscribe and immediately onError with the specified error.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/error.png" alt="">
	 * <p>
	 * @param error the error to signal to each {@link Subscriber}
	 * @param <O> the reified type of the target {@link Subscriber}
	 *
	 * @return a new failed {@link Fluxion}
	 */
	public static <O> Fluxion<O> error(Throwable error) {
		return new FluxionError<O>(error);
	}

	/**
	 * Build a {@link Fluxion} that will only emit an error signal to any new subscriber.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/errorrequest.png" alt="">
	 *
	 * @param whenRequested if true, will onError on the first request instead of subscribe().
	 *
	 * @return a new failed {@link Fluxion}
	 */
	public static <O> Fluxion<O> error(Throwable throwable, boolean whenRequested) {
		return new FluxionError<O>(throwable, whenRequested);
	}

	/**
	 * A simple decoration of the given {@link Publisher} to expose {@link Fluxion} API.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/from.png" alt="">
	 *
	 * @param publisher the {@link Publisher} to decorate the {@link Fluxion} subscriber
	 * @param <T>       the type of values passing through the {@link Fluxion}
	 * @return a {@link Fluxion} view of the passed {@link Publisher}
	 */
	@SuppressWarnings("unchecked")
	public static <T> Fluxion<T> from(final Publisher<? extends T> publisher) {
		if (publisher instanceof Fluxion) {
			return (Fluxion<T>) publisher;
		}

		if (publisher instanceof Supplier) {
			T t = ((Supplier<T>)publisher).get();
			if(t != null){
				return just(t);
			}
		}
		return FluxionSource.wrap(publisher);
	}

	/**
	 * Create a {@link Fluxion} that emits the items contained in the provided {@link Iterable}.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/fromarray.png" alt="">
	 *
	 * @param values The values to {@code onNext)}
	 * @param <T>    type of the values
	 * @return a {@link Fluxion} from emitting array items then complete
	 */
	public static <T> Fluxion<T> fromArray(T[] values) {
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
	 * @param timeout the timeout in milliseconds
	 * @return a new {@link Mono}
	 */
	public static <T> Mono<T> fromFuture(Future<? extends T> future, long timeout) {
		return new MonoFuture<>(future, timeout);
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
	public static <T> Mono<T> fromFuture(Future<? extends T> future, Duration duration) {
		return fromFuture(future, duration.toMillis());
	}

	/**
	 * Create a {@link Fluxion} that emits the items contained in the provided {@link Iterable}.
	 * A new iterator will be created for each subscriber.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/fromiterable.png" alt="">
	 * <p>
	 * @param it the {@link Iterable} to read data from
	 * @param <T> the {@link Iterable} type to fluxion
	 *
	 * @return a new {@link Fluxion}
	 */
	public static <T> Fluxion<T> fromIterable(Iterable<? extends T> it) {
		return new FluxionIterable<>(it);
	}

	/**
	 * A simple decoration of the given {@link Processor} to expose {@link Fluxion} API.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/from.png" alt="">
	 *
	 * @param processor the {@link Processor} to decorate with the {@link Fluxion} API
	 * @param <I>       the type of values observed by the receiving subscriber
	 * @param <O>       the type of values passing through the sending {@link Fluxion}
	 * @return a new {@link Fluxion}
	 */
	@SuppressWarnings("unchecked")
	public static <I, O> FluxionProcessor<I, O> fromProcessor(final Processor<I, O> processor) {
		if (FluxionProcessor.class.isAssignableFrom(processor.getClass())) {
			return (FluxionProcessor<I, O>) processor;
		}
		return new FluxionProcessor<>(processor, processor);
	}

	/**
	 * Create a {@link Fluxion} that emits the items contained in the provided {@link Stream}.
	 * A new iterator will be created for each subscriber.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/fromstream.png" alt="">
	 * <p>
	 * @param s the {@link Stream} to read data from
	 * @param <T> the {@link Stream} type to fluxion
	 *
	 * @return a new {@link Fluxion}
	 */
	public static <T> Fluxion<T> fromStream(Stream<? extends T> s) {
		return new FluxionStream<>(s);
	}


	/**
	 * Create a new {@link Fluxion} that emits an ever incrementing long starting with 0 every N seconds on
	 * the given timer. If demand is not produced in time, an onError will be signalled. The {@link Fluxion} will never
	 * complete.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/interval.png" alt="">
	 * @param period The number of milliseconds to wait before the next increment
	 *
	 * @return a new timed {@link Fluxion}
	 */
	public static Fluxion<Long> interval(long period) {
		return interval(0L, period, Timer.globalOrNew());
	}

	/**
	 * Create a new {@link Fluxion} that emits an ever incrementing long starting with 0 every N period of time unit on
	 * the global timer. If demand is not produced in time, an onError will be signalled. The {@link Fluxion} will never
	 * complete.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/interval.png" alt="">
	 *
	 * @param period The duration to wait before the next increment
	 *
	 * @return a new timed {@link Fluxion}
	 */
	public static Fluxion<Long> interval(Duration period) {
		return interval(period.toMillis());
	}

	/**
	 * Create a new {@link Fluxion} that emits an ever incrementing long starting with 0 every N period of time unit on
	 * the given timer. If demand is not produced in time, an onError will be signalled. The {@link Fluxion} will never
	 * complete.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/interval.png" alt="">
	 *
	 * @param period the period in milliseconds before each following increment
	 * @param timer  the {@link Timer} to schedule on
	 *
	 * @return a new timed {@link Fluxion}
	 */
	public static Fluxion<Long> interval(long period, Timer timer) {
		return interval(0L, period, timer);
	}

	/**
	 * Create a new {@link Fluxion} that emits an ever incrementing long starting with 0 every N period of time unit on
	 * the given timer. If demand is not produced in time, an onError will be signalled. The {@link Fluxion} will never
	 * complete.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/interval.png" alt="">
	 *
	 * @param period the period before each following increment
	 * @param timer  the {@link Timer} to schedule on
	 *
	 * @return a new timed {@link Fluxion}
	 */
	public static Fluxion<Long> interval(Duration period, Timer timer) {
		return interval(period.toMillis(), timer);
	}

	/**
	 * Create a new {@link Fluxion} that emits an ever incrementing long starting with 0 every N period of time unit on
	 * a global timer. If demand is not produced in time, an onError will be signalled. The {@link Fluxion} will never
	 * complete.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/intervald.png" alt="">
	 *
	 * @param delay  the delay in milliseconds to wait before emitting 0l
	 * @param period the period in milliseconds before each following increment
	 *
	 * @return a new timed {@link Fluxion}
	 */
	public static Fluxion<Long> interval(long delay, long period) {
		return interval(delay, period, Timer.globalOrNew());
	}

	/**
	 * Create a new {@link Fluxion} that emits an ever incrementing long starting with 0 every N period of time unit on
	 * a global timer. If demand is not produced in time, an onError will be signalled. The {@link Fluxion} will never
	 * complete.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/intervald.png" alt="">
	 *
	 * @param delay  the delay to wait before emitting 0l
	 * @param period the period before each following increment
	 *
	 * @return a new timed {@link Fluxion}
	 */
	public static Fluxion<Long> interval(Duration delay, Duration period) {
		return interval(delay.toMillis(), period.toMillis());
	}

	/**
	 * Create a new {@link Fluxion} that emits an ever incrementing long starting with 0 every N period of time unit on
	 * the given timer. If demand is not produced in time, an onError will be signalled. The {@link Fluxion} will never
	 * complete.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/intervald.png" alt="">
	 *
	 * @param delay  the timespan in milliseconds to wait before emitting 0l
	 * @param period the period in milliseconds before each following increment
	 * @param timer  the {@link Timer} to schedule on
	 *
	 * @return a new timed {@link Fluxion}
	 */
	public static Fluxion<Long> interval(long delay, long period, Timer timer) {
		return new FluxionInterval(delay, period, timer);
	}

	/**
	 * Create a new {@link Fluxion} that emits an ever incrementing long starting with 0 every N period of time unit on
	 * the given timer. If demand is not produced in time, an onError will be signalled. The {@link Fluxion} will never
	 * complete.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/intervald.png" alt="">
	 *
	 * @param delay  the timespan to wait before emitting 0l
	 * @param period the period before each following increment
	 * @param timer  the {@link Timer} to schedule on
	 *
	 * @return a new timed {@link Fluxion}
	 */
	public static Fluxion<Long> interval(Duration delay, Duration period, Timer timer) {
		return new FluxionInterval(delay.toMillis(), period.toMillis(), timer);
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
	 * @return a zipped {@link Fluxion} as {@link List}
	 */
	@SuppressWarnings({"unchecked", "varargs"})
	@SafeVarargs
	public static <T> Fluxion<List<T>> join(Publisher<? extends T>... sources) {
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
	 * @return a zipped {@link Fluxion} as {@link List}
	 */
	@SuppressWarnings("unchecked")
	public static <T> Fluxion<List<T>> join(Iterable<? extends Publisher<?>> sources) {
		return zip(sources, JOIN_FUNCTION);
	}

	/**
	 * Build a {@link Fluxion} whom data is sourced by the passed element on subscription
	 * request. After all data is being dispatched, a complete signal will be emitted.
	 * <p>
	 *
	 * @param value1 The only value to {@code onNext()}
	 * @param <T>    type of the values
	 * @return a {@link Fluxion} based on the given values
	 */
	public static <T> Fluxion<T> just(T value1) {
		if(value1 == null){
			throw Exceptions.argumentIsNullException();
		}

		return new FluxionJust<T>(value1);
	}

	/**
	 * Create a new {@link Fluxion} that emits the specified items and then complete.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/justn.png" alt="">
	 * <p>
	 * @param values the consecutive data objects to emit
	 * @param <T> the emitted data type
	 *
	 * @return a new {@link Fluxion}
	 */
	@SafeVarargs
	@SuppressWarnings({"unchecked", "varargs"})
	public static <T> Fluxion<T> just(T... values) {
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
	 * @return a fresh Reactive {@link Fluxion} publisher ready to be subscribed
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T> Fluxion<T> merge(Iterable<? extends Publisher<? extends T>> sources) {
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
	 * @return a fresh Reactive {@link Fluxion} publisher ready to be subscribed
	 * @since 2.0, 2.5
	 */
	@SafeVarargs
	@SuppressWarnings({"unchecked", "varargs"})
	public static <T> Fluxion<T> merge(Publisher<? extends T>... sources) {
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
	 * @return a merged {@link Fluxion}
	 */
	public static <T, E extends T> Fluxion<E> merge(Publisher<? extends Publisher<E>> source) {
		return from(Flux.merge(source));
	}

	/**
	 * Create a {@link Fluxion} that will never signal any data, error or completion signal.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/never.png" alt="">
	 * <p>
	 * @param <T> the {@link Subscriber} type target
	 *
	 * @return a never completing {@link Fluxion}
	 */
	@SuppressWarnings("unchecked")
	public static <T> Fluxion<T> never() {
		return (Fluxion<T>) NEVER;
	}

	/**
	 * Build a {@link Fluxion} that will only emit a sequence of incrementing integer from {@code start} to {@code
	 * start + count} then complete.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/range.png" alt="">
	 *
	 * @param start the first integer to be emit
	 * @param count   the number ot times to emit an increment including the first value
	 * @return a ranged {@link Fluxion}
	 */
	public static Fluxion<Integer> range(int start, int count) {
		if(count == 1){
			return just(start);
		}
		if(count == 0){
			return empty();
		}
		return new FluxionRange(start, count);
	}

	/**
	 * Build a {@link FluxionProcessor} whose data are emitted by the most recent emitted {@link Publisher}.
	 * The {@link Fluxion} will complete once both the publishers source and the last switched to {@link Publisher} have
	 * completed.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/switchonnext.png" alt="">
	 *
	 * @param <T> the produced type
	 * @return a {@link FluxionProcessor} accepting publishers and producing T
	 * @since 2.0, 2.5
	 */
	public static <T> FluxionProcessor<Publisher<? extends T>, T> switchOnNext() {
		Processor<Publisher<? extends T>, Publisher<? extends T>> emitter = EmitterProcessor.replay();
		FluxionProcessor<Publisher<? extends T>, T> p = new FluxionProcessor<>(emitter, switchOnNext(emitter));
		p.onSubscribe(EmptySubscription.INSTANCE);
		return p;
	}

	/**
	 * Build a {@link FluxionProcessor} whose data are emitted by the most recent emitted {@link Publisher}.
	 * The {@link Fluxion} will complete once both the publishers source and the last switched to {@link Publisher} have
	 * completed.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/switchonnext.png" alt="">
	 *
	 * @param mergedPublishers The {@link Publisher} of switching {@link Publisher} to subscribe to.
	 * @param <T> the produced type
	 * @return a {@link FluxionProcessor} accepting publishers and producing T
	 * @since 2.0, 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <T> Fluxion<T> switchOnNext(
	  Publisher<Publisher<? extends T>> mergedPublishers) {
		return new FluxionSwitchMap<>(mergedPublishers,
				Function.identity(),
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
	 * @return new {@link Fluxion}
	 */
	public static <T, D> Fluxion<T> using(Callable<? extends D> resourceSupplier, Function<? super D, ? extends
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
	public static <T, D> Fluxion<T> using(Callable<? extends D> resourceSupplier, Function<? super D, ? extends
			Publisher<? extends T>> sourceSupplier, Consumer<? super D> resourceCleanup, boolean eager) {
		return new FluxionUsing<>(resourceSupplier, sourceSupplier, resourceCleanup, eager);
	}

	/**
	 * Create a {@link Fluxion} reacting on subscribe with the passed {@link Consumer}. The argument {@code
	 * sessionConsumer} is executed once by new subscriber to generate a {@link SignalEmitter} context ready to accept
	 * signals.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/yield.png" alt="">
	 * <p>
	 * @param sessionConsumer A {@link Consumer} called once everytime a subscriber subscribes
	 * @param <T> The type of the data sequence
	 *
	 * @return a fresh Reactive {@link Fluxion} publisher ready to be subscribed
	 */
	public static <T> Fluxion<T> yield(Consumer<? super SignalEmitter<T>> sessionConsumer) {
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
	 * @return a zipped {@link Fluxion}
	 *
	 * @since 2.0
	 */
	public static <T1, T2, V> Fluxion<V> zip(Publisher<? extends T1> source1,
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
	 * @return a zipped {@link Fluxion}
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2> Fluxion<Tuple2<T1, T2>> zip(Publisher<? extends T1> source1,
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
	 * @return a zipped {@link Fluxion}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3> Fluxion<Tuple3<T1, T2, T3>> zip(Publisher<? extends T1> source1,
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
	 * @return a zipped {@link Fluxion}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4> Fluxion<Tuple4<T1, T2, T3, T4>> zip(Publisher<? extends T1> source1,
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
	 * @return a zipped {@link Fluxion}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4, T5> Fluxion<Tuple5<T1, T2, T3, T4, T5>> zip(Publisher<? extends T1> source1,
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
	 * @return a zipped {@link Fluxion}
	 *
	 * @since 2.5
	 */
	public static <T1, T2, T3, T4, T5, T6> Fluxion<Tuple6<T1, T2, T3, T4, T5, T6>> zip(Publisher<? extends T1> source1,
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
	 * @return a {@link Fluxion} based on the produced value
	 *
	 * @since 2.5
	 */
	public static <T1, T2, T3, T4, T5, T6, T7> Fluxion<Tuple7<T1, T2, T3, T4, T5, T6, T7>> zip(
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
	 * @return a {@link Fluxion} based on the produced value
	 *
	 * @since 2.5
	 */
	public static <T1, T2, T3, T4, T5, T6, T7, T8> Fluxion<Tuple8<T1, T2, T3, T4, T5, T6, T7, T8>> zip(
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
	 * of the most recent items emitted by each source until any of them completes. Errors will immediately be
	 * forwarded.
	 * The {@link Iterable#iterator()} will be called on each {@link Publisher#subscribe(Subscriber)}.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipt.png" alt="">
	 * <p>
	 * @param sources the {@link Iterable} to iterate on {@link Publisher#subscribe(Subscriber)}
	 * @param <TUPLE>    The type of tuple to use that must match source Publishers type
	 *
	 * @return a zipped {@link Fluxion}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <TUPLE extends Tuple> Fluxion<TUPLE> zip(Iterable<? extends Publisher<?>> sources) {
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
	 * @return a {@link Fluxion} based on the produced value
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public static Fluxion<Tuple> zip(Publisher<? extends Publisher<?>> sources) {
		return zip(sources, Function.identity());
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
	 * @return a {@link Fluxion} based on the produced value
	 *
	 * @since 2.0
	 */
	public static <TUPLE extends Tuple, V> Fluxion<V> zip(
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
	 * @param <O> the combined produced type
	 *
	 * @return a zipped {@link Fluxion}
	 */
	public static <O> Fluxion<O> zip(Iterable<? extends Publisher<?>> sources,
			final Function<? super Object[], ? extends O> combinator) {

		return zip(sources, PlatformDependent.XS_BUFFER_SIZE, combinator);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * produced by the passed combinator function of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 *
	 * The {@link Iterable#iterator()} will be called on each {@link Publisher#subscribe(Subscriber)}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipp.png" alt="">
	 *
	 * @param sources the {@link Iterable} to iterate on {@link Publisher#subscribe(Subscriber)}
	 * @param prefetch the inner source request size
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <O> the combined produced type
	 *
	 * @return a zipped {@link Fluxion}
	 */
	public static <O> Fluxion<O> zip(Iterable<? extends Publisher<?>> sources,
			int prefetch,
			final Function<? super Object[], ? extends O> combinator) {

		if (sources == null) {
			return empty();
		}

		return FluxionSource.wrap(Flux.zip(sources, prefetch, combinator));
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * produced by the passed combinator function of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zip.png" alt="">
	 * <p>
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 * value to signal downstream
	 * @param sources the {@link Publisher} array to iterate on {@link Publisher#subscribe(Subscriber)}
	 * @param <O> the combined produced type
	 *
	 * @return a zipped {@link Fluxion}
	 */
	@SafeVarargs
	@SuppressWarnings("varargs")
	public static <I, O> Fluxion<O> zip(
			final Function<? super Object[], ? extends O> combinator, Publisher<? extends I>... sources) {
		return zip(combinator, PlatformDependent.XS_BUFFER_SIZE, sources);
	}
	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * produced by the passed combinator function of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipp.png" alt="">
	 * <p>
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 * value to signal downstream
	 * @param prefetch individual source request size
	 * @param sources the {@link Publisher} array to iterate on {@link Publisher#subscribe(Subscriber)}
	 * @param <O> the combined produced type
	 *
	 * @return a zipped {@link Fluxion}
	 */
	@SafeVarargs
	@SuppressWarnings("varargs")
	public static <I, O> Fluxion<O> zip(
			final Function<? super Object[], ? extends O> combinator, int prefetch, Publisher<? extends I>... sources) {

		if (sources == null) {
			return empty();
		}

		return FluxionSource.wrap(Flux.zip(combinator, prefetch, sources));
	}

	protected Fluxion() {
	}

	/**
	 * Return a {@code Mono<Void>} that completes when this {@link Fluxion} completes.
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
	 * Return a {@link Fluxion} that emits the sequence of the supplied {@link Publisher} when this {@link Fluxion}
	 * onComplete or onError.
	 * If an error occur, append after the supplied {@link Publisher} is terminated.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/afters.png" alt="">
	 *
	 * @param afterSupplier a {@link Supplier} of {@link Publisher} to emit from after termination
	 * @param <V> the supplied produced type
	 *
	 * @return a new {@link Fluxion} emitting eventually from the supplied {@link Publisher}
	 */
	@SuppressWarnings("unchecked")
	public final <V> Fluxion<V> after(final Supplier<? extends Publisher<V>> afterSupplier) {
		return FluxionSource.wrap(Flux.flatMap(
				Flux.mapSignal(after(), null, new Function<Throwable, Publisher<V>>() {
					@Override
					public Publisher<V> apply(Throwable throwable) {
						return concat(afterSupplier.get(), Fluxion.<V>error(throwable));
					}
				}, afterSupplier),
				Function.identity(), PlatformDependent.SMALL_BUFFER_SIZE, 32, false));
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
	public final Fluxion<O> ambWith(final Publisher<? extends O> other) {
		return FluxionSource.wrap(Flux.amb(this, other));
	}

	/**
	 * Immediately apply the given transformation to this {@link Fluxion} in order to generate a target {@link Publisher} type.
	 *
	 * {@code fluxion.as(Mono::from).subscribe(Subscribers.unbounded()) }
	 *
	 * @param transformer the {@link Function} to immediately map this {@link Fluxion} into a target {@link Publisher}
	 * instance.
	 * @param <P> the returned {@link Publisher} sequence type
	 *
	 * @return the result {@link Publisher} of the immediately applied {@link Function} given this {@link Fluxion}
	 */
	public final <V, P extends Publisher<V>> P as(Function<? super Fluxion<O>, P> transformer) {
		return transformer.apply(this);
	}


	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@link Fluxion} on complete
	 * only.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffer.png" alt="">
	 *
	 * @return a buffered {@link Fluxion} of at most one {@link List}
	 */
	public final Fluxion<List<O>> buffer() {
		return buffer(Integer.MAX_VALUE);
	}

	/**
	 * Collect incoming values into multiple {@link List} buckets that will be pushed into the returned {@link Fluxion}
	 * when the given max size is reached or onComplete is received.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffersize.png" alt="">
	 *
	 * @param maxSize the maximum collected size
	 *
	 * @return a microbatched {@link Fluxion} of {@link List}
	 */
	@SuppressWarnings("unchecked")
	public final Fluxion<List<O>> buffer(final int maxSize) {
		return new FluxionBuffer<>(this, maxSize, (Supplier<List<O>>) LIST_SUPPLIER);
	}

	/**
	 * Collect incoming values into multiple {@link List} that will be pushed into the returned {@link Fluxion} when
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
	 * @return a microbatched {@link Fluxion} of possibly overlapped or gapped {@link List}
	 */
	@SuppressWarnings("unchecked")
	public final Fluxion<List<O>> buffer(final int maxSize, final int skip) {
		return new FluxionBuffer<>(this, maxSize, skip, LIST_SUPPLIER);
	}

	/**
	 * Collect incoming values into multiple {@link List} delimited by the given {@link Publisher} signals.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/bufferboundary.png" alt="">
	 *
	 * @param other the other {@link Publisher}  to subscribe to for emiting and recycling receiving bucket
	 *
	 * @return a microbatched {@link Fluxion} of {@link List} delimited by a {@link Publisher}
	 */
	@SuppressWarnings("unchecked")
	public final Fluxion<List<O>> buffer(final Publisher<?> other) {
		return new FluxionBufferBoundary<>(this, other, LIST_SUPPLIER);
	}

	/**
	 * Collect incoming values into multiple {@link List} delimited by the given {@link Publisher} signals.
	 * Each {@link List} bucket will last until the mapped {@link Publisher} receiving the boundary signal emits,
	 * thus releasing the bucket to the returned {@link Fluxion}.
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
	 * @param bucketOpening a {@link Publisher} to subscribe to for creating new receiving bucket
	 * signals.
	 * @param closeSelector a {@link Publisher} factory provided the opening signal and returning a {@link Publisher}
	 * to subscribe to for emitting relative bucket.
	 *
	 * @return a microbatched  
	 * {@link Fluxion} of {@link List} delimited by an opening {@link Publisher} and a relative closing {@link Publisher}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public final <U, V> Fluxion<List<O>> buffer(final Publisher<U> bucketOpening,
			final Function<? super U, ? extends Publisher<V>> closeSelector) {

		return new FluxionBufferStartEnd<>(this, bucketOpening, closeSelector, LIST_SUPPLIER,
				QueueSupplier.<List<O>>xs());
	}

	/**
	 * Collect incoming values into multiple {@link List} that will be pushed into the returned {@link Fluxion} every
	 * timespan.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespan.png" alt="">
	 *
	 * @param timespan the duration to use to release a buffered list
	 *
	 * @return a microbatched {@link Fluxion} of {@link List} delimited by the given period
	 */
	public final Fluxion<List<O>> buffer(Duration timespan) {
		return buffer(timespan, getTimer());
	}

	/**
	 * Collect incoming values into multiple {@link List} that will be pushed into the returned {@link Fluxion} every
	 * timespan.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespan.png" alt="">
	 *
	 * @param timespan theduration to use to release a buffered list
	 * @param timer the {@link Timer} to schedule on
	 *
	 * @return a microbatched {@link Fluxion} of {@link List} delimited by the given period
	 */
	public final Fluxion<List<O>> buffer(Duration timespan, Timer timer) {
		return buffer(interval(timespan, timer));
	}

	/**
	 * Collect incoming values into multiple {@link List} delimited by the given {@code timeshift} period.
	 * Each {@link List} bucket will last until the {@code timespan} has elapsed,
	 * thus releasing the bucket to the returned {@link Fluxion}.
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
	 * @param timespan the duration to use to release buffered lists
	 * @param timeshift the duration to use to create a new bucket
	 *
	 * @return a microbatched {@link Fluxion} of {@link List} delimited by the given period timeshift and sized by timespan
	 */
	public final Fluxion<List<O>> buffer(final Duration timespan, final Duration timeshift) {
		return buffer(timespan, timeshift, getTimer());
	}

	/**
	 * Collect incoming values into multiple {@link List} delimited by the given {@code timeshift} period.
	 * Each {@link List} bucket will last until the {@code timespan} has elapsed,
	 * thus releasing the bucket to the returned {@link Fluxion}.
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
	 * @param timespan the duration to use to release buffered lists
	 * @param timeshift the duration to use to create a new bucket
	 * @param timer the {@link Timer} to run on
	 *
	 * @return a microbatched {@link Fluxion} of {@link List} delimited by the given period timeshift and sized by timespan

	 */
	public final Fluxion<List<O>> buffer(final Duration timespan,
			final Duration timeshift,
			final Timer timer) {
		if (timespan.equals(timeshift)) {
			return buffer(timespan, timer);
		}
		return buffer(interval(Duration.ZERO, timeshift, timer), new Function<Long, Publisher<Long>>() {
			@Override
			public Publisher<Long> apply(Long aLong) {
				return Mono.delay(timespan, timer);
			}
		});
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@link Fluxion} every timespan
	 * OR maxSize items.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespansize.png" alt="">

	 *
	 * @param maxSize the max collected size
	 * @param timespan the timeout in milliseconds to use to release a buffered list
	 *
	 * @return a microbatched {@link Fluxion} of {@link List} delimited by given size or a given period timeout
	 */
	public final Fluxion<List<O>> buffer(int maxSize, long timespan) {
		return buffer(maxSize, timespan, getTimer());
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@link Fluxion} every timespan
	 * OR maxSize items.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespansize.png" alt="">

	 *
	 * @param maxSize the max collected size
	 * @param timespan the timeout to use to release a buffered list
	 *
	 * @return a microbatched {@link Fluxion} of {@link List} delimited by given size or a given period timeout
	 */
	public final Fluxion<List<O>> buffer(int maxSize, Duration timespan) {
		return buffer(maxSize, timespan.toMillis());
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@link Fluxion} every timespan
	 * OR maxSize items
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespansize.png" alt="">
	 *
	 * @param maxSize the max collected size
	 * @param timespan the timeout to use to release a buffered list
	 * @param timer the {@link Timer} to run on
	 *
	 * @return a microbatched {@link Fluxion} of {@link List} delimited by given size or a given period timeout
	 */
	public final Fluxion<List<O>> buffer(final int maxSize,
			final long timespan,
			final Timer timer) {
		return new FluxionBufferTimeOrSize<>(this, maxSize, timespan, timer);
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@link Fluxion} every timespan
	 * OR maxSize items
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespansize.png" alt="">
	 *
	 * @param maxSize the max collected size
	 * @param timespan the timeout to use to release a buffered list
	 * @param timer the {@link Timer} to run on
	 *
	 * @return a microbatched {@link Fluxion} of {@link List} delimited by given size or a given period timeout
	 */
	public final Fluxion<List<O>> buffer(final int maxSize,
			final Duration timespan,
			final Timer timer) {
		return buffer(maxSize, timespan.toMillis(), timer);
	}

	/**
	 * Stage incoming values into a {@link java.util.PriorityQueue} that will be re-ordered and signaled to the
	 * returned {@link Fluxion} when sequence is complete. PriorityQueue will use the {@link Comparable} interface from
	 * an incoming data signal. Due to it's unbounded nature (must accumulate in priority queue before emitting all
	 * sequence), this operator should not be used on hot or large volume sequences.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffersort.png" alt="">
	 *
	 * @return a buffering {@link Fluxion} whose values are re-ordered using a {@link PriorityQueue}.
	 *
	 * @since 2.0, 2.5
	 */
	public final Fluxion<O> bufferSort() {
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
	 * @return a buffering {@link Fluxion} whose values are re-ordered using a {@link PriorityQueue}.
	 *
	 * @since 2.0, 2.5
	 */
	public final Fluxion<O> bufferSort(final Comparator<? super O> comparator) {
		return FluxionSource.wrap(collect(new Supplier<PriorityQueue<O>>() {
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
	 * Turn this {@link Fluxion} into a hot source and cache last emitted signals for further {@link Subscriber}.
	 * Will retain up to {@link PlatformDependent#SMALL_BUFFER_SIZE} onNext signals. Completion and Error will also be
	 * replayed.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/cache.png" alt="">
	 *
	 * @return a replaying {@link Fluxion}
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> cache() {
		return cache(PlatformDependent.SMALL_BUFFER_SIZE);
	}

	/**
	 * Turn this {@link Fluxion} into a hot source and cache last emitted signals for further {@link Subscriber}.
	 * Will retain up to the given history size onNext signals. Completion and Error will also be
	 * replayed.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/cache.png" alt="">
	 *
	 * @param history number of events retained in history excluding complete and error
	 *
	 * @return a replaying {@link Fluxion}
	 *
	 * @since 2.5
	 */
	public final Fluxion<O> cache(int history) {
		return multicast(EmitterProcessor.<O>replay(history)).autoConnect();
	}

	/**
	 * Cast the current {@link Fluxion} produced type into a target produced type.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/cast.png" alt="">
	 *
	 * @param <E> the {@link Fluxion} output type
	 *
	 * @return a casted {link Stream}
	 *
	 * @since 2.0
	 */
	@SuppressWarnings({"unchecked", "unused"})
	public final <E> Fluxion<E> cast(final Class<E> stream) {
		return (Fluxion<E>) this;
	}

	/**
	 * Collect the {@link Fluxion} sequence with the given collector and supplied container on subscribe.
	 * The collected result will be emitted when this sequence completes.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/collect.png" alt="">

	 *
	 * @param <E> the {@link Fluxion} collected container type
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
	 * @return a concatenated {@link Fluxion}
	 */
	public final <V> Fluxion<V> concatMap(final Function<? super O, Publisher<? extends V>> mapper) {
		return new FluxionConcatMap<>(this, mapper, QueueSupplier.<O>xs(), PlatformDependent.XS_BUFFER_SIZE,
				FluxionConcatMap.ErrorMode.IMMEDIATE);
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
	 * @return a concatenated {@link Fluxion}
	 *
	 * @since 2.5
	 */
	public final <V> Fluxion<V> concatMapDelayError(final Function<? super O, Publisher<? extends V>> mapper) {
		return new FluxionConcatMap<>(this, mapper, QueueSupplier.<O>xs(), PlatformDependent.XS_BUFFER_SIZE,
				FluxionConcatMap.ErrorMode.END);
	}

	/**
	 * Concatenate emissions of this {@link Fluxion} with the provided {@link Publisher} (no interleave).
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/concat.png" alt="">
	 *
	 * @param other the {@link Publisher} sequence to concat after this {@link Fluxion}
	 *
	 * @return a concatenated {@link Fluxion}
	 */
	@SuppressWarnings("unchecked")
	public final Fluxion<O> concatWith(final Publisher<? extends O> other) {
		return new FluxionConcatArray<>(this, other);
	}

	/**
	 * Subscribe a {@link Consumer} to this {@link Fluxion} that will consume all the
	 * sequence.  If {@link Fluxion#getCapacity()} returns an integer value, the {@link Subscriber} will use it as a
	 * prefetch strategy: first request N, then when 25% of N is left to be received on onNext, request N x 0.75. <p>
	 * For a passive version that observe and forward incoming data see {@link #doOnNext(java.util.function.Consumer)}
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
	 * Subscribe {@link Consumer} to this {@link Fluxion} that will consume all the
	 * sequence.  If {@link Fluxion#getCapacity()} returns an integer value, the {@link Subscriber} will use it as a
	 * prefetch strategy: first request N, then when 25% of N is left to be received on onNext, request N x 0.75. <p>
	 * For a passive version that observe and forward incoming data see
	 * {@link #doOnNext(java.util.function.Consumer)} and {@link #doOnError(java.util.function.Consumer)}.
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
	 * Subscribe {@link Consumer} to this {@link Fluxion} that will consume all the
	 * sequence.  If {@link Fluxion#getCapacity()} returns an integer value, the {@link Subscriber} will use it as a
	 * prefetch strategy: first request N, then when 25% of N is left to be received on onNext, request N x 0.75. <p>
	 * For a passive version that observe and forward incoming data see {@link #doOnNext(java.util.function.Consumer)},
	 * {@link #doOnError(java.util.function.Consumer)} and {@link #doOnComplete(Runnable)},
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
	 * {@link Consumer} to this {@link Fluxion} that will wait for interaction via {@link ManualSubscriber#request} to
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
	 * {@link Consumer} to this {@link Fluxion} that will wait for interaction via {@link ManualSubscriber#request} to
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
	 * {@link Consumer} to this {@link Fluxion} that will wait for interaction via {@link ManualSubscriber#request} to
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
	 * Subscribe a {@link Consumer} to this {@link Fluxion} that will wait for the returned {@link Publisher} to emit
	 * a {@link Long} demand. The demand {@link Function} factory will be given a {@link Fluxion} of requests starting
	 * with {@literal 0} then emitting the N - 1 request. The request sequence can be composed and deferred to
	 * produce a throttling effect.
	 * <ul>
	 * <li>If this {@link Fluxion} terminates, the request sequence will be terminated too.</li>
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
			final Function<? super Fluxion<Long>, ? extends Publisher<? extends Long>> requestMapper) {
		InterruptableSubscriber<O> consumerAction =
				InterruptableSubscriber.adaptive(consumer,
						(Function<? super Publisher<Long>, ? extends Publisher<? extends Long>>)requestMapper,
				Broadcaster.<Long>create());

		subscribe(consumerAction);
		return consumerAction;
	}

	/**
	 * Subscribe a {@link Consumer} to this {@link Fluxion} that will wait for the returned {@link Function} to produce
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
		return consumeWhen(consumer, new Function<Fluxion<Long>, Publisher<? extends Long>>() {
			@Override
			public Publisher<? extends Long> apply(Fluxion<Long> longStream) {
				return longStream.map(requestMapper);
			}
		});
	}

	/**
	 * Counts the number of values in this {@link Fluxion}.
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
	 * Introspect this {@link Fluxion} graph
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
	 * @return a new {@link Fluxion}
	 */
	public final Fluxion<O> defaultIfEmpty(final O defaultV) {
		return new FluxionDefaultIfEmpty<>(this, defaultV);
	}

	/**
	 * Delay this {@link Fluxion} signals to {@link Subscriber#onNext} until the given period in seconds elapses.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/delayonnext.png" alt="">
	 *
	 * @param seconds period to delay each {@link Subscriber#onNext} call
	 *
	 * @return a throttled {@link Fluxion}
	 *
	 * @since 2.5
	 */
	public final Fluxion<O> delay(long seconds) {
		return delay(Duration.ofSeconds(seconds));
	}


	/**
	 * Delay this {@link Fluxion} signals to {@link Subscriber#onNext} until the given period elapses.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/delayonnext.png" alt="">
	 *
	 * @param delay duration to delay each {@link Subscriber#onNext} call
	 *
	 * @return a throttled {@link Fluxion}
	 *
	 * @since 2.5
	 */
	public final Fluxion<O> delay(final Duration delay) {
		return concatMap(new Function<O, Publisher<? extends O>>() {
			@Override
			public Publisher<? extends O> apply(final O o) {
				Timer timer = getTimer();
				return Mono.delay(delay, timer != null ? timer : Timer.globalOrNew())
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
	 * Delay the {@link Fluxion#subscribe(Subscriber) subscription} to this {@link Fluxion} source until the given
	 * period elapses.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/delaysubscription.png" alt="">
	 *
	 * @param delay period in seconds before subscribing this {@link Fluxion}
	 *
	 * @return a delayed {@link Fluxion}
	 *
	 * @since 2.5
	 */
	public final Fluxion<O> delaySubscription(long delay) {
		return delaySubscription(Duration.ofSeconds(delay));
	}

	/**
	 * Delay the {@link Fluxion#subscribe(Subscriber) subscription} to this {@link Fluxion} source until the given
	 * period elapses.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/delaysubscription.png" alt="">
	 *
	 * @param delay duration before subscribing this {@link Fluxion}
	 *
	 * @return a delayed {@link Fluxion}
	 *
	 * @since 2.5
	 */
	public final Fluxion<O> delaySubscription(Duration delay) {
		Timer timer = getTimer();
		return delaySubscription(Mono.delay(delay, timer != null ? timer : Timer.globalOrNew()));
	}

	/**
	 * Delay the subscription to the main source until another Publisher
	 * signals a value or completes.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/delaysubscriptionp.png" alt="">
	 *
	 * @param subscriptionDelay a
	 * {@link Publisher} to signal by next or complete this {@link Fluxion#subscribe(Subscriber)}
	 * @param <U> the other source type
	 *
	 * @return a delayed {@link Fluxion}
	 *
	 * @since 2.5
	 */
	public final <U> Fluxion<O> delaySubscription(Publisher<U> subscriptionDelay) {
		return new FluxionDelaySubscription<>(this, subscriptionDelay);
	}

	/**
	 * A "phantom-operator" working only if this
	 * {@link Fluxion} is a emits onNext, onError or onComplete {@link Signal}. The relative {@link Subscriber}
	 * callback will be invoked, error {@link Signal} will trigger onError and complete {@link Signal} will trigger
	 * onComplete.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/dematerialize.png" alt="">
	 *
	 * @return a dematerialized {@link Fluxion}
	 */
	@SuppressWarnings("unchecked")
	public final <X> Fluxion<X> dematerialize() {
		Fluxion<Signal<X>> thiz = (Fluxion<Signal<X>>) this;
		return new FluxionDematerialize<>(thiz);
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
	 * {@code fluxion.dispatchOn(WorkQueueProcessor.create()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param scheduler a checked factory for {@link Consumer} of {@link Runnable}
	 *
	 * @return a {@link Fluxion} consuming asynchronously
	 */
	public final Fluxion<O> dispatchOn(final Callable<? extends Consumer<Runnable>> scheduler) {
		return FluxionSource.wrap(Flux.dispatchOn(this, scheduler, true,
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
	 * {@code fluxion.dispatchOn(ForkJoinPool.commonPool()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param executorService an {@link ExecutorService} to dispatch events downstream
	 *
	 * @return a {@link Fluxion} consuming asynchronously
	 */
	public final Fluxion<O> dispatchOn(final ExecutorService executorService) {
		return dispatchOn(new ExecutorServiceScheduler(executorService));
	}

	/**
	 * For each {@link Subscriber}, tracks this {@link Fluxion} values that have been seen and
	 * filters out duplicates.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/distinct.png" alt="">
	 *
	 * @return a filtering {@link Fluxion} with unique values
	 */
	@SuppressWarnings("unchecked")
	public final Fluxion<O> distinct() {
		return new FluxionDistinct<>(this, HASHCODE_EXTRACTOR, hashSetSupplier());
	}

	/**
	 * For each {@link Subscriber}, tracks this {@link Fluxion} values that have been seen and
	 * filters out duplicates given the extracted key.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/distinctk.png" alt="">
	 *
	 * @param keySelector function to compute comparison key for each element
	 *
	 * @return a filtering {@link Fluxion} with values having distinct keys
	 */
	public final <V> Fluxion<O> distinct(final Function<? super O, ? extends V> keySelector) {
		return new FluxionDistinct<>(this, keySelector, hashSetSupplier());
	}

	/**
	 * Filters out subsequent and repeated elements.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/distinctuntilchanged.png" alt="">

	 *
	 * @return a filtering {@link Fluxion} with conflated repeated elements
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final Fluxion<O> distinctUntilChanged() {
		return new FluxionDistinctUntilChanged<O, O>(this, HASHCODE_EXTRACTOR);
	}

	/**
	 * Filters out subsequent and repeated elements provided a matching extracted key.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/distinctuntilchangedk.png" alt="">

	 *
	 * @param keySelector function to compute comparison key for each element
	 *
	 * @return a filtering {@link Fluxion} with conflated repeated elements given a comparison key
	 *
	 * @since 2.0
	 */
	public final <V> Fluxion<O> distinctUntilChanged(final Function<? super O, ? extends V> keySelector) {
		return new FluxionDistinctUntilChanged<>(this, keySelector);
	}

	/**
	 * Triggered after the {@link Fluxion} terminates, either by completing downstream successfully or with an error.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doafterterminate.png" alt="">
	 * <p>
	 * @param afterTerminate the callback to call after {@link Subscriber#onComplete} or {@link Subscriber#onError}
	 *
	 * @return an observed  {@link Fluxion}
	 */
	public final Fluxion<O> doAfterTerminate(final Runnable afterTerminate) {
		if (this instanceof Fuseable) {
			return new FluxionPeekFuseable<>(this, null, null, null, null, afterTerminate, null, null);
		}
		return new FluxionPeek<>(this, null, null, null, null, afterTerminate, null, null);
	}

	/**
	 * Triggered when the {@link Fluxion} is cancelled.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/dooncancel.png" alt="">
	 * <p>
	 * @param onCancel the callback to call on {@link Subscription#cancel}
	 *
	 * @return an observed  {@link Fluxion}
	 */
	public final Fluxion<O> doOnCancel(final Runnable onCancel) {
		if (this instanceof Fuseable) {
			return new FluxionPeekFuseable<>(this, null, null, null, null, null, null, onCancel);
		}
		return new FluxionPeek<>(this, null, null, null, null, null, null, onCancel);
	}

	/**
	 * Triggered when the {@link Fluxion} completes successfully.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/dooncomplete.png" alt="">
	 * <p>
	 * @param onComplete the callback to call on {@link Subscriber#onComplete}
	 *
	 * @return an observed  {@link Fluxion}
	 */
	public final Fluxion<O> doOnComplete(final Runnable onComplete) {
		if (this instanceof Fuseable) {
			return new FluxionPeekFuseable<>(this, null, null, null, onComplete, null, null, null);
		}
		return new FluxionPeek<>(this, null, null, null, onComplete, null, null, null);
	}

	/**
	 * Triggered when the {@link Fluxion} completes with an error.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonerror.png" alt="">
	 * <p>
	 * @param onError the callback to call on {@link Subscriber#onError}
	 *
	 * @return an observed  {@link Fluxion}
	 */
	public final Fluxion<O> doOnError(final Consumer<Throwable> onError) {
		if (this instanceof Fuseable) {
			return new FluxionPeekFuseable<>(this, null, null, onError, null, null, null, null);
		}
		return new FluxionPeek<>(this, null, null, onError, null, null, null, null);
	}

	/**
	 * Triggered when the {@link Fluxion} completes with an error matching the given exception type.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonerrorw.png" alt="">
	 *
	 * @param exceptionType the type of exceptions to handle
	 * @param onError the error handler for each error
	 * @param <E> type of the error to handle
	 *
	 * @return an observed  {@link Fluxion}
	 * @since 2.0, 2.5
	 */
	public final <E extends Throwable> Fluxion<O> doOnError(final Class<E> exceptionType,
			final Consumer<E> onError) {
		return new FluxionWhenError<O, E>(this, exceptionType, onError);
	}

	/**
	 * Triggered when the {@link Fluxion} emits an item.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonnext.png" alt="">
	 * <p>
	 * @param onNext the callback to call on {@link Subscriber#onNext}
	 *
	 * @return an observed  {@link Fluxion}
	 */
	public final Fluxion<O> doOnNext(final Consumer<? super O> onNext) {
		if (this instanceof Fuseable) {
			return new FluxionPeekFuseable<>(this, null, onNext, null, null, null, null, null);
		}
		return new FluxionPeek<>(this, null, onNext, null, null, null, null, null);
	}

	/**
	 * Attach a {@link LongConsumer} to this {@link Fluxion} that will observe any request to this {@link Fluxion}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonrequest.png" alt="">
	 *
	 * @param consumer the consumer to invoke on each request
	 *
	 * @return an observed  {@link Fluxion}
	 */
	public final Fluxion<O> doOnRequest(final LongConsumer consumer) {
		if (this instanceof Fuseable) {
			return new FluxionPeekFuseable<>(this, null, null, null, null, null, consumer, null);
		}
		return new FluxionPeek<>(this, null, null, null, null, null, consumer, null);
	}

	/**
	 * Triggered when the {@link Fluxion} is subscribed.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonsubscribe.png" alt="">
	 * <p>
	 * @param onSubscribe the callback to call on {@link Subscriber#onSubscribe}
	 *
	 * @return an observed  {@link Fluxion}
	 */
	public final Fluxion<O> doOnSubscribe(final Consumer<? super Subscription> onSubscribe) {
		if (this instanceof Fuseable) {
			return new FluxionPeekFuseable<>(this, onSubscribe, null, null, null, null, null, null);
		}
		return new FluxionPeek<>(this, onSubscribe, null, null, null, null, null, null);
	}

	/**
	 * Triggered when the {@link Fluxion} terminates, either by completing successfully or with an error.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonterminate.png" alt="">
	 * <p>
	 * @param onTerminate the callback to call on {@link Subscriber#onComplete} or {@link Subscriber#onError}
	 *
	 * @return an observed  {@link Fluxion}
	 */
	public final Fluxion<O> doOnTerminate(final Runnable onTerminate) {
		if (this instanceof Fuseable) {
			return new FluxionPeekFuseable<>(this, null, null, null, onTerminate, null, null, null);
		}
		return new FluxionPeek<>(this, null, null, null, onTerminate, null, null, null);
	}

	/**
	 * Triggered when the {@link Fluxion} completes with an error matching the given exception type.
	 * Provide a non null second argument to the {@link BiConsumer} if the captured {@link Exception} wraps a final
	 * cause value.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonerrorv.png" alt="">
	 *
	 * @param exceptionType the type of exceptions to handle
	 * @param onError the error handler for each error
	 * @param <E> type of the error to handle
	 *
	 * @return an observed  {@link Fluxion}
	 */
	public final <E extends Throwable> Fluxion<O> doOnValueError(final Class<E> exceptionType,
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
	 * Map this {@link Fluxion} sequence into {@link reactor.core.tuple.Tuple2} of T1 {@link Long} timemillis and T2
	 * {@link <T>} associated data. The timemillis corresponds to the elapsed time between the subscribe and the first
	 * next signal OR between two next signals.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/elapsed.png" alt="">
	 *
	 * @return a transforming {@link Fluxion} that emits tuples of time elapsed in milliseconds and matching data
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final Fluxion<Tuple2<Long, O>> elapsed() {
		return new FluxionElapsed(this);
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
	 * Emit only the last value of each batch counted from this {@link Fluxion} sequence.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/every.png" alt="">
	 *
	 * @param batchSize the batch size to count
	 *
	 * @return a new {@link Fluxion} whose values are the last value of each batch
	 */
	public final Fluxion<O> every(final int batchSize) {
		return new FluxionEvery<O>(this, batchSize);
	}

	/**
	 * Emit only the first value of each batch counted from this {@link Fluxion} sequence.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/everyfirst.png" alt="">
	 *
	 * @param batchSize the batch size to use
	 *
	 * @return a new {@link Fluxion} whose values are the first value of each batch
	 */
	public final Fluxion<O> everyFirst(final int batchSize) {
		return new FluxionEvery<O>(this, batchSize, true);
	}

	/**
	 * Emit a single boolean true if any of the values of this {@link Fluxion} sequence match
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
	 * @return a new {@link Fluxion} with <code>true</code> if any value satisfies a predicate and <code>false</code>
	 * otherwise
	 *
	 * @since 2.0, 2.5
	 */
	public final Mono<Boolean> exists(final Predicate<? super O> predicate) {
		return new MonoAny<>(this, predicate);
	}

	/**
	 * Evaluate each accepted value against the given {@link Predicate}. If the predicate test succeeds, the value is
	 * passed into the new {@link Fluxion}. If the predicate test fails, the value is ignored and a request of 1 is
	 * emitted.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/filter.png" alt="">
	 *
	 * @param p the {@link Predicate} to test values against
	 *
	 * @return a new {@link Fluxion} containing only values that pass the predicate test
	 */
	public final Fluxion<O> filter(final Predicate<? super O> p) {
		if (this instanceof Fuseable) {
			return new FluxionFilterFuseable<>(this, p);
		}
		return new FluxionFilter<>(this, p);
	}

	/**
	 * Transform the items emitted by this {@link Fluxion} into Publishers, then flatten the emissions from those by
	 * merging them into a single {@link Fluxion}, so that they may interleave.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/flatmap.png" alt="">
	 *
	 * @param mapper the {@link Function} to transform input sequence into N sequences {@link Publisher}
	 * @param <V> the merged output sequence type
	 *
	 * @return a new {@link Fluxion}
	 */
	public final <V> Fluxion<V> flatMap(final Function<? super O, ? extends Publisher<? extends V>> mapper) {
		return FluxionSource.wrap(Flux.flatMap(this,
				mapper,
				PlatformDependent.SMALL_BUFFER_SIZE,
				PlatformDependent.XS_BUFFER_SIZE,
				false));
	}

	/**
	 * Transform the items emitted by this {@link Fluxion} into Publishers, then flatten the emissions from those by
	 * merging them into a single {@link Fluxion}, so that they may interleave. The concurrency argument allows to
	 * control how many merged {@link Publisher} can happen in parallel.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/flatmapc.png" alt="">
	 *
	 * @param mapper the {@link Function} to transform input sequence into N sequences {@link Publisher}
	 * @param concurrency the maximum in-flight elements from this {@link Fluxion} sequence
	 * @param <V> the merged output sequence type
	 *
	 * @return a new {@link Fluxion}
	 *
	 * @since 2.5
	 */
	public final <V> Fluxion<V> flatMap(final Function<? super O, ? extends Publisher<? extends V>> mapper, int
			concurrency) {
		return flatMap(mapper, concurrency, PlatformDependent.XS_BUFFER_SIZE);
	}

	/**
	 * Transform the items emitted by this {@link Fluxion} into Publishers, then flatten the emissions from those by
	 * merging them into a single {@link Fluxion}, so that they may interleave. The concurrency argument allows to
	 * control how many merged {@link Publisher} can happen in parallel. The prefetch argument allows to give an
	 * arbitrary prefetch size to the merged {@link Publisher}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/flatmapc.png" alt="">
	 *
	 * @param mapper the {@link Function} to transform input sequence into N sequences {@link Publisher}
	 * @param concurrency the maximum in-flight elements from this {@link Fluxion} sequence
	 * @param prefetch the maximum in-flight elements from each inner {@link Publisher} sequence
	 * @param <V> the merged output sequence type
	 *
	 * @return a merged {@link Fluxion}
	 *
	 * @since 2.5
	 */
	public final <V> Fluxion<V> flatMap(final Function<? super O, ? extends Publisher<? extends V>> mapper, int
			concurrency, int prefetch) {
		return FluxionSource.wrap(Flux.flatMap(this,
				mapper,
				concurrency,
				prefetch,
				false));
	}

	/**
	 * Transform the signals emitted by this {@link Fluxion} into Publishers, then flatten the emissions from those by
	 * merging them into a single {@link Fluxion}, so that they may interleave.
	 * OnError will be transformed into completion signal after its mapping callback has been applied.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/flatmaps.png" alt="">
	 * <p>
	 * @param mapperOnNext the {@link Function} to call on next data and returning a sequence to merge
	 * @param mapperOnError the {@link Function} to call on error signal and returning a sequence to merge
	 * @param mapperOnComplete the {@link Function} to call on complete signal and returning a sequence to merge
	 * @param <R> the output {@link Publisher} type target
	 *
	 * @return a merged {@link Fluxion}
	 */
	@SuppressWarnings("unchecked")
	public final <R> Fluxion<R> flatMap(Function<? super O, ? extends Publisher<? extends R>> mapperOnNext,
			Function<Throwable, ? extends Publisher<? extends R>> mapperOnError,
			Supplier<? extends Publisher<? extends R>> mapperOnComplete) {
		return FluxionSource.wrap(Flux.flatMap(
				Flux.mapSignal(this, mapperOnNext, mapperOnError, mapperOnComplete),
				Function.identity(), PlatformDependent.SMALL_BUFFER_SIZE, 32, false)
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
	 * @return a merged {@link Fluxion} containing the combined values
	 *
	 * @since 2.5
	 */
	public final <T1, T2> Fluxion<Tuple2<T1, T2>> forkJoin(
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
	 * @return a merged {@link Fluxion} containing the combined values
	 *
	 * @since 2.5
	 */
	public final <T1, T2, T3> Fluxion<Tuple3<T1, T2, T3>> forkJoin(
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
	 * @return a merged {@link Fluxion} containing the combined values
	 *
	 * @since 2.5
	 */
	public final <T1, T2, T3, T4> Fluxion<Tuple4<T1, T2, T3, T4>> forkJoin(
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
	 * @return a merged {@link Fluxion} containing the combined values
	 *
	 * @since 2.5
	 */
	public final <T1, T2, T3, T4, T5> Fluxion<Tuple5<T1, T2, T3, T4, T5>> forkJoin(
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
	 * @return a merged {@link Fluxion} containing the combined values
	 *
	 * @since 2.5
	 */
	public final <T1, T2, T3, T4, T5, T6> Fluxion<Tuple6<T1, T2, T3, T4, T5, T6>> forkJoin(
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
	 * @return a merged {@link Fluxion} containing the combined values
	 *
	 * @since 2.5
	 */
	public final <T1, T2, T3, T4, T5, T6, T7> Fluxion<Tuple7<T1, T2, T3, T4, T5, T6, T7>> forkJoin(
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
	 * @return a merged {@link Fluxion} containing the combined values
	 *
	 * @since 2.5
	 */
	public final <T1, T2, T3, T4, T5, T6, T7, T8> Fluxion<Tuple8<T1, T2, T3, T4, T5, T6, T7, T8>> forkJoin(
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
		                 .replace(Fluxion.class.getSimpleName(), "");
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
	 * Re-route this sequence into dynamically created {@link Fluxion} for each unique key evaluated by the given
	 * key mapper.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/groupby.png" alt="">
	 *
	 * @param keyMapper the key mapping {@link Function} that evaluates an incoming data and returns a key.
	 *
	 * @return a {@link Fluxion} of {@link GroupedFluxion} grouped sequences
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final <K> Fluxion<GroupedFluxion<K, O>> groupBy(final Function<? super O, ? extends K> keyMapper) {
		return groupBy(keyMapper, Function.identity());
	}

	/**
	 * Re-route this sequence into dynamically created {@link Fluxion} for each unique key evaluated by the given
	 * key mapper. It will use the given value mapper to extract the element to route.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/groupby.png" alt="">
	 *
	 * @param keyMapper the key mapping function that evaluates an incoming data and returns a key.
	 * @param valueMapper the value mapping function that evaluates which data to extract for re-routing.
	 *
	 * @return a {@link Fluxion} of {@link GroupedFluxion} grouped sequences
	 *
	 * @since 2.5
	 */
	public final <K, V> Fluxion<GroupedFluxion<K, V>> groupBy(Function<? super O, ? extends K> keyMapper,
			Function<? super O, ? extends V> valueMapper) {
		return new FluxionGroupBy<>(this, keyMapper, valueMapper,
				QueueSupplier.<GroupedFluxion<K, V>>small(),
				QueueSupplier.<V>unbounded(),
				PlatformDependent.SMALL_BUFFER_SIZE);
	}

	/**
	 * Emit a single boolean true if this {@link Fluxion} sequence has at least one element.
	 * <p>
	 * The implementation uses short-circuit logic and completes with true on onNext.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/haselements.png" alt="">
	 *
	 * @return a new {@link Mono} with <code>true</code> if any value is emitted and <code>false</code>
	 * otherwise
	 */
	public final Mono<Boolean> hasElements() {
		return new MonoHasElements<>(this);
	}

	/**
	 * Hides the identities of this {@link Fluxion} and its {@link Subscription}
	 * as well.
	 *
	 * @return a new {@link Fluxion} defeating any {@link Publisher} / {@link Subscription} feature-detection
	 */
	public final Fluxion<O> hide() {
		return new FluxionHide<>(this);
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
	 * @return a zipped {@link Fluxion} as {@link List}
	 */
	@SuppressWarnings("unchecked")
	public final <T> Fluxion<List<T>> joinWith(Publisher<T> other) {
		return zipWith(other, (BiFunction<Object, Object, List<T>>) JOIN_BIFUNCTION);
	}

	/**
	 * Signal the last element observed before complete signal.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/last.png" alt="">
	 *
	 * @return a limited {@link Fluxion}
	 *
	 * @since 2.0
	 */
	public final Mono<O> last() {
		return MonoSource.wrap(new FluxionTakeLast<>(this, 1));
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
	 * @return a lifted {@link Fluxion}
	 * @since 2.5
	 */
	public <V> Fluxion<V> lift(final Function<Subscriber<? super V>, Subscriber<? super O>> lifter) {
		return new FluxionSource.Operator<>(this, lifter);
	}

	/**
	 * Observe all Reactive Streams signals and use {@link Logger} support to handle trace implementation. Default will
	 * use {@link Level#INFO} and java.util.logging. If SLF4J is available, it will be used instead.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/log.png" alt="">
	 * <p>
	 * The default log category will be "reactor.core.publisher.FluxLog".
	 *
	 * @return an observed  {@link Fluxion}
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> log() {
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
	 * @return an observed  {@link Fluxion}
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> log(String category) {
		return log(category, Logger.ALL);
	}

	/**
	 * Observe Reactive Streams signals matching the passed flags {@code options} and use {@link Logger} support to
	 * handle trace
	 * implementation. Default will use java.util.logging. If SLF4J is available, it will be used instead.
	 *
	 * Options allow fine grained filtering of the traced signal, for instance to only capture onNext and onError:
	 * <pre>
	 *     fluxion.log("category", Logger.ON_NEXT | LOGGER.ON_ERROR)
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/log.png" alt="">
	 *
	 * @param category to be mapped into logger configuration (e.g. org.springframework.reactor).
	 * @param options a flag option that can be mapped with {@link Logger#ON_NEXT} etc.
	 *
	 * @return an observed  {@link Fluxion}
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> log(final String category, int options) {
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
	 *     fluxion.log("category", Level.INFO, Logger.ON_NEXT | LOGGER.ON_ERROR)
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/log.png" alt="">
	 *
	 * @param category to be mapped into logger configuration (e.g. org.springframework.reactor).
	 * @param level the level to enforce for this tracing Flux
	 * @param options a flag option that can be mapped with {@link Logger#ON_NEXT} etc.
	 *
	 * @return an observed  {@link Fluxion}
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> log(final String category, Level level, int options) {
		return FluxionSource.wrap(Flux.log(this, category, level, options));
	}

	/**
	 * Transform the items emitted by this {@link Fluxion} by applying a function to each item.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/map.png" alt="">
	 * <p>
	 * @param mapper the transforming {@link Function}
	 * @param <V> the transformed type
	 *
	 * @return a transformed {@link Fluxion}
	 */
	public final <V> Fluxion<V> map(final Function<? super O, ? extends V> mapper) {
		if (this instanceof Fuseable) {
			return new FluxionMapFuseable<>(this, mapper);
		}
		return new FluxionMap<>(this, mapper);
	}

	/**
	 * Transform the incoming onNext, onError and onComplete signals into {@link reactor.rx.Signal}.
	 * Since the error is materialized as a {@code Signal}, the propagation will be stopped and onComplete will be
	 * emitted. Complete signal will first emit a {@code Signal.complete()} and then effectively complete the fluxion.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/materialize.png" alt="">
	 *
	 * @return a {@link Fluxion} of materialized {@link Signal}
	 */
	public final Fluxion<Signal<O>> materialize() {
		return new FluxionMaterialize<>(this);
	}

	/**
	 * Merge emissions of this {@link Fluxion} with the provided {@link Publisher}, so that they may interleave.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/merge.png" alt="">
	 *
	 * @param other the {@link Publisher} to merge with
	 *
	 * @return a merged {@link Fluxion}
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> mergeWith(final Publisher<? extends O> other) {
		return FluxionSource.wrap(Flux.merge(this, other));
	}

	/**
	 * Prepare a {@link ConnectableFluxion} which shares this {@link Fluxion} sequence and dispatches values to
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
	 * @return a new {@link ConnectableFluxion} whose values are broadcasted to all subscribers once connected
	 *
	 * @since 2.5
	 */
	public final ConnectableFluxion<O> multicast() {
		return publish();
	}

	/**
	 * Prepare a
	 * {@link ConnectableFluxion} which subscribes this {@link Fluxion} sequence to the given {@link Processor}.
	 * The {@link Processor} will be itself subscribed by child {@link Subscriber} when {@link ConnectableFluxion#connect()}
	 *  is invoked manually or automatically via {@link ConnectableFluxion#autoConnect} and {@link ConnectableFluxion#refCount}.
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
	 * @param processor the {@link Processor} reference to subscribe to this {@link Fluxion} and share.
	 *
	 * @return a new {@link ConnectableFluxion} whose values are broadcasted to supported subscribers once connected via {@link Processor}
	 *
	 * @since 2.5
	 */
	public final ConnectableFluxion<O> multicast(final Processor<? super O, ? extends O> processor) {
		return multicast(new Supplier<Processor<? super O, ? extends O>>() {
			@Override
			public Processor<? super O, ? extends O> get() {
				return processor;
			}
		});
	}

	/**
	 * Prepare a
	 * {@link ConnectableFluxion} which subscribes this {@link Fluxion} sequence to a supplied {@link Processor}
	 * when
	 * {@link ConnectableFluxion#connect()} is invoked manually or automatically via {@link ConnectableFluxion#autoConnect} and {@link ConnectableFluxion#refCount}.
	 * The {@link Processor} will be itself subscribed by child {@link Subscriber}.
	 *  Note that some {@link Processor} do not support multi-subscribe, multicast is non opinionated in fact and
	 *  focuses on subscribe lifecycle.
	 *
	 * This will effectively turn any type of sequence into a hot sequence by sharing a single {@link Subscription}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/multicastp.png" alt="">
	 *
	 * @param processorSupplier the {@link Processor} {@link Supplier} to call, subscribe to this {@link Fluxion} and
	 * share.
	 *
	 * @return a new {@link ConnectableFluxion} whose values are broadcasted to supported subscribers once connected via {@link Processor}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public final ConnectableFluxion<O> multicast(
			Supplier<? extends Processor<? super O, ? extends O>> processorSupplier) {
		return multicast(processorSupplier, Function.identity());
	}

	/**
	 * Prepare a
	 * {@link ConnectableFluxion} which subscribes this {@link Fluxion} sequence to the given {@link Processor}.
	 * The {@link Processor} will be itself subscribed by child {@link Subscriber} when {@link ConnectableFluxion#connect()}
	 *  is invoked manually or automatically via {@link ConnectableFluxion#autoConnect} and {@link ConnectableFluxion#refCount}.
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
	 * @param processor the {@link Processor} reference to subscribe to this {@link Fluxion} and share.
	 * @param selector a {@link Function} receiving a {@link Fluxion} derived from the supplied {@link Processor} and
	 * returning the end {@link Publisher} subscribed by a unique {@link Subscriber}
	 * @param <U> produced type from the given selector
	 *
	 * @return a new {@link ConnectableFluxion} whose values are broadcasted to supported subscribers once connected via {@link Processor}
	 * 
	 * @since 2.5
	 */
	public final <U> ConnectableFluxion<U> multicast(final Processor<? super O, ? extends O>
			processor, Function<Fluxion<O>, ? extends Publisher<? extends U>> selector) {
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
	 * {@link ConnectableFluxion} which subscribes this {@link Fluxion} sequence to a supplied {@link Processor}
	 * when
	 * {@link ConnectableFluxion#connect()} is invoked manually or automatically via {@link ConnectableFluxion#autoConnect} and {@link ConnectableFluxion#refCount}.
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
	 * @param processorSupplier the {@link Processor} {@link Supplier} to call, subscribe to this {@link Fluxion} and
	 * share.
	 * @param selector a {@link Function} receiving a {@link Fluxion} derived from the supplied {@link Processor} and
	 * returning the end {@link Publisher} subscribed by a unique {@link Subscriber}
	 * @param <U> produced type from the given selector
	 *
	 * @return a new {@link ConnectableFluxion} whose values are broadcasted to supported subscribers once connected via {@link Processor}
	 *
	 * @since 2.5
	 */
	public final <U> ConnectableFluxion<U> multicast(Supplier<? extends Processor<? super O, ? extends O>>
			processorSupplier, Function<Fluxion<O>, ? extends Publisher<? extends U>> selector) {
		return new FluxionMulticast<>(this, processorSupplier, selector);
	}

	/**
	 * Make this
	 * {@link Fluxion} subscribed N concurrency times for each child {@link Subscriber}. In effect, if this {@link Fluxion}
	 * is a cold replayable source, duplicate sequences will be emitted to the passed {@link GroupedFluxion} partition
	 * . If this {@link Fluxion} is a hot sequence, {@link GroupedFluxion} partitions might observe different values, e
	 * .g. subscribing to a {@link reactor.core.publisher.WorkQueueProcessor}.
	 * <p>Each partition is merged back using {@link #flatMap flatMap} and the result sequence might be interleaved.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/multiplex.png" alt="">
	 *
	 * @param fn the indexed via
	 * {@link GroupedFluxion#key()} sequence transformation to be merged in the returned {@link Fluxion}
	 * @param <V> the type of the return value of the transformation function
	 *
	 * @return a merged {@link Fluxion} produced from N concurrency transformed sequences
	 *
	 * @since 2.5
	 */
	public final <V> Fluxion<V> multiplex(final int concurrency,
			final Function<GroupedFluxion<Integer, O>, Publisher<V>> fn) {
		Assert.isTrue(concurrency > 0, "Must subscribe once at least, concurrency set to " + concurrency);

		Publisher<V> pub;
		final List<Publisher<? extends V>> publisherList = new ArrayList<>(concurrency);

		for (int i = 0; i < concurrency; i++) {
			final int index = i;
			pub = fn.apply(new GroupedFluxion<Integer, O>() {

				@Override
				public Integer key() {
					return index;
				}

				@Override
				public long getCapacity() {
					return Fluxion.this.getCapacity();
				}

				@Override
				public Timer getTimer() {
					return Fluxion.this.getTimer();
				}

				@Override
				public void subscribe(Subscriber<? super O> s) {
					Fluxion.this.subscribe(s);
				}
			});

			if (concurrency == 1) {
				return from(pub);
			}
			else {
				publisherList.add(pub);
			}
		}

		return FluxionSource.wrap(Flux.merge(publisherList));
	}

	/**
	 * Emit the current instance of the {@link Fluxion}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/nest.png" alt="">
	 *
	 * @return a new {@link Fluxion} whose only value will be the current {@link Fluxion}
	 *
	 * @since 2.0
	 */
	public final Fluxion<Fluxion<O>> nest() {
		return just(this);
	}

	/**
	 * Emit only the first observed item from this {@link Fluxion} sequence.
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
	 * <p> Blocking a synchronous {@link Fluxion} might lead to unexpected starvation of downstream request
	 * replenishing or upstream hot event producer.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressureblock.png" alt="">
	 *
	 * @return a blocking {@link Fluxion}
	 *
	 * @since 2.5
	 */
	public final Fluxion<O> onBackpressureBlock() {
		return onBackpressureBlock(WaitStrategy.blocking());
	}

	/**
	 * Request an unbounded demand and block incoming onNext signals if not enough demand is requested downstream.
	 * <p> Blocking a synchronous {@link Fluxion} might lead to unexpected starvation of downstream request
	 * replenishing or upstream hot event producer.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressureblock.png" alt="">
	 *
	 * @param waitStrategy a {@link WaitStrategy} to trade off higher latency blocking wait for CPU resources
	 * (spinning, yielding...)
	 *
	 * @return a blocking {@link Fluxion}
	 *
	 * @since 2.5
	 */
	public final Fluxion<O> onBackpressureBlock(WaitStrategy waitStrategy) {
		return new FluxionBlock<>(this, waitStrategy);
	}

	/**
	 * Request an unbounded demand and push the returned {@link Fluxion}, or park the observed elements if not enough
	 * demand is requested downstream.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressurebuffer.png" alt="">
	 *
	 * @return a buffering {@link Fluxion}
	 *
	 * @since 2.0, 2.5
	 */
	public final Fluxion<O> onBackpressureBuffer() {
		return new FluxionBackpressureBuffer<>(this);
	}

	/**
	 * Request an unbounded demand and push the returned {@link Fluxion}, or drop the observed elements if not enough
	 * demand is requested downstream.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressuredrop.png" alt="">
	 *
	 * @return a dropping {@link Fluxion}
	 *
	 * @since 2.0, 2.5
	 */
	public final Fluxion<O> onBackpressureDrop() {
		return new FluxionDrop<>(this);
	}

	/**
	 * Request an unbounded demand and push the returned {@link Fluxion}, or drop and notify dropping {@link Consumer}
	 * with the observed elements if not enough demand is requested downstream.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressuredropc.png" alt="">
	 *
	 * @return a dropping {@link Fluxion}
	 *
	 * @since 2.5
	 */
	public final Fluxion<O> onBackpressureDrop(Consumer<? super O> onDropped) {
		return new FluxionDrop<>(this, onDropped);
	}

	/**
	 * Request an unbounded demand and push the returned
	 * {@link Fluxion}, or emit onError fom {@link Exceptions#failWithOverflow} if not enough demand is requested
	 * downstream.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressureerror.png" alt="">
	 *
	 * @return an erroring {@link Fluxion} on backpressure
	 *
	 * @since 2.0, 2.5
	 */
	public final Fluxion<O> onBackpressureError() {
		return onBackpressureDrop(new Consumer<O>() {
			@Override
			public void accept(O o) {
				Exceptions.failWithOverflow();
			}
		});
	}

	/**
	 * Request an unbounded demand and push the returned {@link Fluxion}, or only keep the most recent observed item
	 * if not enough demand is requested downstream.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressurelatest.png" alt="">
	 *
	 * @return a dropping {@link Fluxion} that will only keep a reference to the last observed item
	 *
	 * @since 2.5
	 */
	public final Fluxion<O> onBackpressureLatest() {
		return new FluxionLatest<>(this);
	}

	/**
	 * Subscribe to a returned fallback publisher when any error occurs.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onerrorresumewith.png" alt="">
	 * <p>
	 * @param fallback the {@link Function} mapping the error to a new {@link Publisher} sequence
	 *
	 * @return a fallbacking {@link Fluxion}
	 */
	public final Fluxion<O> onErrorResumeWith(final Function<Throwable, ? extends Publisher<? extends O>> fallback) {
		return FluxionSource.wrap(Flux.onErrorResumeWith(this, fallback));
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
	 * @return {@link Fluxion}
	 */
	public final Fluxion<O> onErrorReturn(final O fallback) {
		return switchOnError(just(fallback));
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Fluxion} for each unique key evaluated by the given
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
	 * @return a partitioning {@link Fluxion} whose values are {@link GroupedFluxion} of all active partionned sequences
	 *
	 * @since 2.0
	 */
	public final Fluxion<GroupedFluxion<Integer, O>> partition() {
		return partition(SchedulerGroup.DEFAULT_POOL_SIZE);
	}

	/**
	 *
	 * Re-route incoming values into a dynamically created {@link Fluxion} for each unique key evaluated by the given
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
	 * @return a partitioning {@link Fluxion} whose values are {@link GroupedFluxion} of all active partionned sequences
	 *
	 * @since 2.0
	 */
	public final Fluxion<GroupedFluxion<Integer, O>> partition(final int buckets) {
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
	 * Prepare a {@link ConnectableFluxion} which shares this {@link Fluxion} sequence and dispatches values to
	 * subscribers in a backpressure-aware manner. Prefetch will default to {@link PlatformDependent#SMALL_BUFFER_SIZE}.
	 * This will effectively turn any type of sequence into a hot sequence.
	 * <p>
	 * Backpressure will be coordinated on {@link Subscription#request} and if any {@link Subscriber} is missing
	 * demand (requested = 0), multicast will pause pushing/pulling.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/publish.png" alt="">
	 * 
	 * @return a new {@link ConnectableFluxion}
	 */
	public final ConnectableFluxion<O> publish() {
		return publish(PlatformDependent.SMALL_BUFFER_SIZE);
	}

	/**
	 * Prepare a {@link ConnectableFluxion} which shares this {@link Fluxion} sequence and dispatches values to
	 * subscribers in a backpressure-aware manner. This will effectively turn any type of sequence into a hot sequence.
	 * <p>
	 * Backpressure will be coordinated on {@link Subscription#request} and if any {@link Subscriber} is missing
	 * demand (requested = 0), multicast will pause pushing/pulling.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/publish.png" alt="">
	 * 
	 * @param prefetch bounded requested demand
	 * 
	 * @return a new {@link ConnectableFluxion}
	 */
	public final ConnectableFluxion<O> publish(int prefetch) {
		return new FluxionPublish<>(this, prefetch, QueueSupplier.<O>get(prefetch));
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
	 * {@code fluxion.publishOn(WorkQueueProcessor.create()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param schedulerFactory a checked factory for {@link Consumer} of {@link Runnable}
	 *
	 * @return a {@link Fluxion} publishing asynchronously
	 */
	public final Fluxion<O> publishOn(final Callable<? extends Consumer<Runnable>> schedulerFactory) {
		return FluxionSource.wrap(Flux.publishOn(this, schedulerFactory));
	}

	/**
	 * Run subscribe, onSubscribe and request on a supplied {@link ExecutorService}.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/publishon.png" alt="">
	 * <p>
	 * {@code fluxion.publishOn(ForkJoinPool.commonPool()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param executorService an {@link ExecutorService} to run requests and subscribe on
	 *
	 * @return a {@link Fluxion} publishing asynchronously
	 */
	public final Fluxion<O> publishOn(final ExecutorService executorService) {
		return publishOn(new ExecutorServiceScheduler(executorService));
	}

	/**
	 * Aggregate the values from this {@link Fluxion} sequence into an object of the same type than the
	 * emitted items. The left/right {@link BiFunction} arguments are the N-1 and N item, ignoring sequence
	 * with 0 or 1 element only.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/aggregate.png" alt="">
	 *
	 * @param aggregator the aggregating {@link BiFunction}
	 *
	 * @return a reduced {@link Fluxion}
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
	 * Accumulate the values from this {@link Fluxion} sequence into an object matching an initial value type.
	 * The arguments are the N-1 or {@literal initial} value and N current item .
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/reduce.png" alt="">
	 *
	 * @param accumulator the reducing {@link BiFunction}
	 * @param initial the initial left argument to pass to the reducing {@link BiFunction}
	 * @param <A> the type of the initial and reduced object
	 *
	 * @return a reduced {@link Fluxion}
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
	 * Accumulate the values from this {@link Fluxion} sequence into an object matching an initial value type.
	 * The arguments are the N-1 or {@literal initial} value and N current item .
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/reduce.png" alt="">
	 *
	 * @param accumulator the reducing {@link BiFunction}
	 * @param initial the initial left argument supplied on subscription to the reducing {@link BiFunction}
	 * @param <A> the type of the initial and reduced object
	 *
	 * @return a reduced {@link Fluxion}
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
	 * @return an indefinitively repeated {@link Fluxion} on onComplete
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> repeat() {
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
	 * @return an eventually repeated {@link Fluxion} on onComplete
	 *
	 * @since 2.5
	 */
	public final Fluxion<O> repeat(BooleanSupplier predicate) {
		return new FluxionRepeatPredicate<>(this, predicate);
	}

	/**
	 * Repeatedly subscribe to the source if the predicate returns true after completion of the previous subscription.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/repeatn.png" alt="">
	 *
	 * @param numRepeat the number of times to re-subscribe on onComplete
	 *
	 * @return an eventually repeated {@link Fluxion} on onComplete up to number of repeat specified
	 *
	 * @since 2.0, 2.5
	 */
	public final Fluxion<O> repeat(final long numRepeat) {
		return new FluxionRepeat<O>(this, numRepeat);
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
	 * @return an eventually repeated {@link Fluxion} on onComplete up to number of repeat specified OR matching
	 * predicate
	 *
	 * @since 2.0, 2.5
	 */
	public final Fluxion<O> repeat(final long numRepeat, BooleanSupplier predicate) {
		return new FluxionRepeatPredicate<>(this, countingBooleanSupplier(predicate, numRepeat));
	}

	/**
	 * Repeatedly subscribe to this {@link Fluxion} when a companion sequence signals a number of emitted elements in
	 * response to the fluxion completion signal.
	 * <p>If the companion sequence signals when this {@link Fluxion} is active, the repeat
	 * attempt is suppressed and any terminal signal will terminate this {@link Fluxion} with the same signal immediately.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/repeatwhen.png" alt="">
	 *
	 * @param whenFactory the {@link Function} providing a {@link Fluxion} signalling an exclusive number of
	 * emitted elements on onComplete and returning a {@link Publisher} companion.
	 *
	 * @return an eventually repeated {@link Fluxion} on onComplete when the companion {@link Publisher} produces an
	 * onNext signal
	 *
	 * @since 2.0, 2.5
	 */
	public final Fluxion<O> repeatWhen(final Function<Fluxion<Long>, ? extends Publisher<?>> whenFactory) {
		return new FluxionRepeatWhen<O>(this, whenFactory);
	}

	/**
	 * Request this {@link Fluxion} when a companion sequence signals a demand.
	 * <p>If the companion sequence terminates when this
	 * {@link Fluxion} is active, it  will terminate this {@link Fluxion} with the same signal immediately.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/requestwhen.png" alt="">
	 *
	 * @param throttleFactory the
	 * {@link Function} providing a {@link Fluxion} signalling downstream requests and returning a {@link Publisher}
	 * companion that coordinate requests upstream.
	 *
	 * @return a request throttling {@link Fluxion}
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> requestWhen(final Function<? super Fluxion<? extends Long>, ? extends Publisher<? extends
			Long>> throttleFactory) {
		return new FluxionThrottleRequestWhen<O>(this, getTimer(), throttleFactory);
	}

	/**
	 * Re-subscribes to this {@link Fluxion} sequence if it signals any error
	 * either indefinitely.
	 * <p>
	 * The times == Long.MAX_VALUE is treated as infinite retry.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/retry.png" alt="">
	 *
	 * @return a re-subscribing {@link Fluxion} on onError
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> retry() {
		return retry(Long.MAX_VALUE);
	}
	
	/**
	 * Re-subscribes to this {@link Fluxion} sequence if it signals any error
	 * either indefinitely or a fixed number of times.
	 * <p>
	 * The times == Long.MAX_VALUE is treated as infinite retry.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/retryn.png" alt="">
	 *
	 * @param numRetries the number of times to tolerate an error
	 *
	 * @return a re-subscribing {@link Fluxion} on onError up to the specified number of retries.
	 *
	 * @since 2.0, 2.5
	 */
	public final Fluxion<O> retry(long numRetries) {
		return new FluxionRetry<O>(this, numRetries);
	}

	/**
	 * Re-subscribes to this {@link Fluxion} sequence if it signals any error
	 * and the given {@link Predicate} matches otherwise push the error downstream.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/retryb.png" alt="">
	 *
	 * @param retryMatcher the predicate to evaluate if retry should occur based on a given error signal
	 *
	 * @return a re-subscribing {@link Fluxion} on onError if the predicates matches.
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> retry(Predicate<Throwable> retryMatcher) {
		return new FluxionRetryPredicate<>(this, retryMatcher);
	}

	/**
	 * Re-subscribes to this {@link Fluxion} sequence up to the specified number of retries if it signals any
	 * error and the given {@link Predicate} matches otherwise push the error downstream.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/retrynb.png" alt="">
	 *
	 * @param numRetries the number of times to tolerate an error
	 * @param retryMatcher the predicate to evaluate if retry should occur based on a given error signal
	 *
	 * @return a re-subscribing {@link Fluxion} on onError up to the specified number of retries and if the predicate
	 * matches.
	 *
	 * @since 2.0, 2.5
	 */
	public final Fluxion<O> retry(final long numRetries, final Predicate<Throwable> retryMatcher) {
		return new FluxionRetryPredicate<>(this, countingPredicate(retryMatcher, numRetries));
	}

	/**
	 * Retries this {@link Fluxion} when a companion sequence signals
	 * an item in response to this {@link Fluxion} error signal
	 * <p>
	 * <p>If the companion sequence signals when the {@link Fluxion} is active, the retry
	 * attempt is suppressed and any terminal signal will terminate the {@link Fluxion} source with the same signal
	 * immediately.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/retrywhen.png" alt="">
	 *
	 * @param whenFactory the
	 * {@link Function} providing a {@link Fluxion} signalling any error from the source sequence and returning a {@link Publisher} companion.
	 *
	 * @return a re-subscribing {@link Fluxion} on onError when the companion {@link Publisher} produces an
	 * onNext signal
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> retryWhen(final Function<Fluxion<Throwable>, ? extends Publisher<?>> whenFactory) {
		return new FluxionRetryWhen<O>(this, whenFactory);
	}

	/**
	 * Emit latest value for every given period of ti,e.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/sampletimespan.png" alt="">
	 *
	 * @param timespan the period in second to emit the latest observed item
	 *
	 * @return a sampled {@link Fluxion} by last item over a period of time
	 */
	public final Fluxion<O> sample(long timespan) {
		return sample(Duration.ofSeconds(timespan));
	}

	/**
	 * Emit latest value for every given period of time.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/sampletimespan.png" alt="">
	 *
	 * @param timespan the duration to emit the latest observed item
	 *
	 * @return a sampled {@link Fluxion} by last item over a period of time
	 */
	public final Fluxion<O> sample(Duration timespan) {
		return sample(interval(timespan));
	}

	/**
	 * Sample this {@link Fluxion} and emit its latest value whenever the sampler {@link Publisher}
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
	 * @return a sampled {@link Fluxion} by last item observed when the sampler {@link Publisher} signals
	 */
	public final <U> Fluxion<O> sample(Publisher<U> sampler) {
		return new FluxionSample<>(this, sampler);
	}

	/**
	 * Take a value from this {@link Fluxion} then use the duration provided to skip other values.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/samplefirsttimespan.png" alt="">
	 *
	 * @param timespan the period in seconds to exclude others values from this sequence
	 *
	 * @return a sampled {@link Fluxion} by first item over a period of time
	 */
	public final Fluxion<O> sampleFirst(final long timespan) {
		return sampleFirst(Duration.ofSeconds(timespan));
	}

	/**
	 * Take a value from this {@link Fluxion} then use the duration provided to skip other values.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/samplefirsttimespan.png" alt="">
	 *
	 * @param timespan the duration to exclude others values from this sequence
	 *
	 * @return a sampled {@link Fluxion} by first item over a period of time
	 */
	public final Fluxion<O> sampleFirst(final Duration timespan) {
		return sampleFirst(new Function<O, Publisher<Long>>() {
			@Override
			public Publisher<Long> apply(O o) {
				return Mono.delay(timespan);
			}
		});
	}

	/**
	 * Take a value from this {@link Fluxion} then use the duration provided by a
	 * generated Publisher to skip other values until that sampler {@link Publisher} signals.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/samplefirst.png" alt="">
	 *
	 * @param samplerFactory select a {@link Publisher} companion to signal onNext or onComplete to stop excluding
	 * others values from this sequence
	 * @param <U> the companion reified type
	 *
	 * @return a sampled {@link Fluxion} by last item observed when the sampler signals
	 */
	public final <U> Fluxion<O> sampleFirst(Function<? super O, ? extends Publisher<U>> samplerFactory) {
		return new FluxionThrottleFirst<>(this, samplerFactory);
	}


	/**
	 * Emit the last value from this {@link Fluxion} only if there were no new values emitted
	 * during the time window provided by a publisher for that particular last value.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/sampletimeout.png" alt="">
	 *
	 * @param throttlerFactory select a {@link Publisher} companion to signal onNext or onComplete to stop checking
	 * others values from this sequence and emit the selecting item
	 * @param <U> the companion reified type
	 *
	 * @return a sampled {@link Fluxion} by last single item observed before a companion {@link Publisher} emits
	 */
	@SuppressWarnings("unchecked")
	public final <U> Fluxion<O> sampleTimeout(Function<? super O, ? extends Publisher<U>> throttlerFactory) {
		return new FluxionThrottleTimeout<>(this, throttlerFactory, QueueSupplier.unbounded(PlatformDependent
				.XS_BUFFER_SIZE));
	}

	/**
	 * Emit the last value from this {@link Fluxion} only if there were no newer values emitted
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
	 * @return a sampled {@link Fluxion} by last single item observed before a companion {@link Publisher} emits
	 */
	@SuppressWarnings("unchecked")
	public final <U> Fluxion<O> sampleTimeout(Function<? super O, ? extends Publisher<U>> throttlerFactory, long
			maxConcurrency) {
		if(maxConcurrency == Long.MAX_VALUE){
			return sampleTimeout(throttlerFactory);
		}
		return new FluxionThrottleTimeout<>(this, throttlerFactory, QueueSupplier.get(maxConcurrency));
	}

	/**
	 * Accumulate this {@link Fluxion} values with an accumulator {@link BiFunction} and
	 * returns the intermediate results of this function.
	 * <p>
	 * Unlike {@link #scan(Object, BiFunction)}, this operator doesn't take an initial value
	 * but treats the first {@link Fluxion} value as initial value.
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
	 * @return an accumulating {@link Fluxion}
	 *
	 * @since 1.1, 2.0
	 */
	public final Fluxion<O> scan(final BiFunction<O, O, O> accumulator) {
		return new FluxionAccumulate<>(this, accumulator);
	}

	/**
	 * Aggregate this {@link Fluxion} values with the help of an accumulator {@link BiFunction}
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
	 * @return an accumulating {@link Fluxion} starting with initial state
	 *
	 * @since 1.1, 2.0
	 */
	public final <A> Fluxion<A> scan(final A initial, final BiFunction<A, ? super O, A> accumulator) {
		return new FluxionScan<>(this, initial, accumulator);
	}

	/**
	 * Expect and emit a single item from this {@link Fluxion} source or signal
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
	 * Expect and emit a single item from this {@link Fluxion} source or signal
	 * {@link java.util.NoSuchElementException} (or a default generated value) for empty source,
	 * {@link IndexOutOfBoundsException} for a multi-item source.
	 * 
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/singleordefault.png" alt="">
	 * @param defaultSupplier a {@link Supplier} of a single fallback item if this {@link Fluxion} is empty
	 *
	 * @return a {@link Mono} with the eventual single item or a supplied default value
	 */
	public final Mono<O> singleOrDefault(Supplier<? extends O> defaultSupplier) {
		return new MonoSingle<>(this, defaultSupplier);
	}

	/**
	 * Expect and emit a zero or single item from this {@link Fluxion} source or
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
	 * Skip next the specified number of elements from this {@link Fluxion}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/skip.png" alt="">
	 *
	 * @param skipped the number of times to drop
	 *
	 * @return a dropping {@link Fluxion} until the specified skipped number of elements
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> skip(long skipped) {
		if (skipped > 0) {
			return new FluxionSkip<>(this, skipped);
		}
		else {
			return this;
		}
	}

	/**
	 * Skip elements from this {@link Fluxion} for the given time period.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/skiptime.png" alt="">
	 *
	 * @param timespan the time window to exclude next signals
	 *
	 * @return a dropping {@link Fluxion} until the end of the given timespan
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> skip(Duration timespan) {
		if(!timespan.isZero()) {
			Timer timer = getTimer();
			Assert.isTrue(timer != null, "Timer can't be found, try assigning an environment to the fluxion");
			return skipUntil(Mono.delay(timespan, timer));
		}
		else{
			return this;
		}
	}

	/**
	 * Skip the last specified number of elements from this {@link Fluxion}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/skiplast.png" alt="">
	 *
	 * @param n the number of elements to ignore before completion
	 *
	 * @return a dropping {@link Fluxion} for the specified skipped number of elements before termination
	 *
	 * @since 2.5
	 */
	public final Fluxion<O> skipLast(int n) {
		return new FluxionSkipLast<>(this, n);
	}

	/**
	 * Skip values from this {@link Fluxion} until a specified {@link Publisher} signals
	 * an onNext or onComplete.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/skipuntil.png" alt="">
	 *
	 * @param other the {@link Publisher} companion to coordinate with to stop skipping
	 *
	 * @return a dropping {@link Fluxion} until the other {@link Publisher} emits
	 *
	 * @since 2.5
	 */
	public final Fluxion<O> skipUntil(final Publisher<?> other) {
		return new FluxionSkipUntil<>(this, other);
	}

	/**
	 * Skips values from this {@link Fluxion} while a {@link Predicate} returns true for the value.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/skipwhile.png" alt="">
	 *
	 * @param skipPredicate the {@link Predicate} evaluating to true to keep skipping.
	 *
	 * @return a dropping {@link Fluxion} while the {@link Predicate} matches
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> skipWhile(final Predicate<? super O> skipPredicate) {
		return new FluxionSkipWhile<>(this, skipPredicate);
	}

	/**
	 * Prepend the given {@link Iterable} before this {@link Fluxion} sequence.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/startwithi.png" alt="">
	 *
	 * @return a prefixed {@link Fluxion} with given {@link Iterable}
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> startWith(final Iterable<O> iterable) {
		return startWith(fromIterable(iterable));
	}

	/**
	 * Prepend the given values before this {@link Fluxion} sequence.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/startwithv.png" alt="">
	 *
	 * @return a prefixed {@link Fluxion} with given values
	 *
	 * @since 2.0
	 */
	@SafeVarargs
	@SuppressWarnings("varargs")
	public final Fluxion<O> startWith(final O... values) {
		return startWith(just(values));
	}

	/**
	 * Prepend the given {@link Publisher} sequence before this {@link Fluxion} sequence.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/startwith.png" alt="">
	 *
	 * @return a prefixed {@link Fluxion} with given {@link Publisher} sequence
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> startWith(final Publisher<? extends O> publisher) {
		if (publisher == null) {
			return this;
		}
		return concat(publisher, this);
	}

	/**
	 * Transform this {@link Fluxion} into a lazy {@link Stream} blocking on next calls.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tostream.png"
	 * alt="">
	 *
	 * @return a {@link Stream} of unknown size with onClose attached to {@link Subscription#cancel()}
	 */
	public Stream<O> stream() {
		return stream(getCapacity() == -1 ? Long.MAX_VALUE : getCapacity());
	}

	/**
	 * Transform this {@link Fluxion} into a lazy {@link Stream} blocking on next calls.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tostream.png"
	 * alt="">
	 *
	 * @return a {@link Stream} of unknown size with onClose attached to {@link Subscription#cancel()}
	 */
	public Stream<O> stream(long batchSize) {
		final Supplier<Queue<O>> provider;
		provider = QueueSupplier.get(batchSize);
		return new BlockingIterable<>(this, batchSize, provider).stream();
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
	 * {@link Consumer} to this {@link Fluxion} that will wait for interaction via {@link ManualSubscriber#request} to
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
	 * {@code fluxion.subscribeWith(WorkQueueProcessor.create()).subscribe(Subscribers.unbounded()) }
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
	 * @return an alternating {@link Fluxion} on source onComplete without elements
	 *
	 * @since 2.5
	 */
	public final Fluxion<O> switchIfEmpty(final Publisher<? extends O> alternate) {
		return new FluxionSwitchIfEmpty<>(this, alternate);
	}

	/**
	 * Switch to a new {@link Publisher} generated via a {@link Function} whenever this {@link Fluxion} produces an item.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/switchmap.png" alt="">
	 *
	 * @param fn the transformation function
	 * @param <V> the type of the return value of the transformation function
	 *
	 * @return an alternating {@link Fluxion} on source onNext
	 *
	 * @since 1.1, 2.0
	 */
	@SuppressWarnings("unchecked")
	public final <V> Fluxion<V> switchMap(final Function<? super O, Publisher<? extends V>> fn) {
		return new FluxionSwitchMap<>(this, fn, QueueSupplier.xs(), PlatformDependent.XS_BUFFER_SIZE);
	}

	/**
	 * Subscribe to the given fallback {@link Publisher} if an error is observed on this {@link Fluxion}
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/switchonerror.png" alt="">
	 *
	 * @param fallback the alternate {@link Publisher}
	 *
	 * @return an alternating {@link Fluxion} on source onError
	 */
	public final Fluxion<O> switchOnError(final Publisher<? extends O> fallback) {
		return FluxionSource.wrap(Flux.onErrorResumeWith(this, new Function<Throwable, Publisher<? extends O>>() {
			@Override
			public Publisher<? extends O> apply(Throwable throwable) {
				return fallback;
			}
		}));
	}

	/**
	 * Take only the first N values from this {@link Fluxion}.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/take.png" alt="">
	 * <p>
	 * If N is zero, the {@link Subscriber} gets completed if this {@link Fluxion} completes, signals an error or
	 * signals its first value (which is not not relayed though).
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/take0.png" alt="">
	 * @param n the number of items to emit from this {@link Fluxion}
	 *
	 * @return a size limited {@link Fluxion}
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> take(final long n) {
		return new FluxionTake<O>(this, n);
	}

	/**
	 * Relay values from this {@link Fluxion} until the given time period elapses.
	 * <p>
	 * If the time period is zero, the {@link Subscriber} gets completed if this {@link Fluxion} completes, signals an
	 * error or
	 * signals its first value (which is not not relayed though).
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/taketime.png" alt="">
	 *
	 * @param timespan the time window of items to emit from this {@link Fluxion}
	 *
	 * @return a time limited {@link Fluxion}
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> take(Duration timespan) {
		if (!timespan.isZero()) {
			Timer timer = getTimer();
			Assert.isTrue(timer != null, "Timer can't be found, try assigning an environment to the fluxion");
			return takeUntil(Mono.delay(timespan, timer));
		}
		else {
			return take(0);
		}
	}

	/**
	 * Emit the last N values this {@link Fluxion} emitted before its completion.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/takelast.png" alt="">
	 *
	 * @param n the number of items from this {@link Fluxion} to retain and emit on onComplete
	 *
	 * @return a terminating {@link Fluxion} sub-sequence
	 *
	 * @since 2.5
	 */
	public final Fluxion<O> takeLast(int n) {
		return new FluxionTakeLast<>(this, n);
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
	 * @return an eventually limited {@link Fluxion}
	 *
	 * @since 2.5
	 */
	public final Fluxion<O> takeUntil(final Predicate<? super O> stopPredicate) {
		return new FluxionTakeUntilPredicate<>(this, stopPredicate);
	}

	/**
	 * Relay values from this {@link Fluxion} until the given {@link Publisher} emits.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/takeuntil.png" alt="">
	 *
	 * @param other the {@link Publisher} to signal when to stop replaying signal from this {@link Fluxion}
	 *
	 * @return an eventually limited {@link Fluxion}
	 *
	 * @since 2.5
	 */
	public final Fluxion<O> takeUntil(final Publisher<?> other) {
		return new FluxionTakeUntil<>(this, other);
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
	 * @return an eventually limited {@link Fluxion}
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> takeWhile(final Predicate<? super O> continuePredicate) {
		return new FluxionTakeWhile<O>(this, continuePredicate);
	}

	/**
	 * Create a {@link FluxionTap} that maintains a reference to the last value seen by this {@link Fluxion}. The {@link FluxionTap} is
	 * continually updated when new values pass through the {@link Fluxion}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tap.png" alt="">
	 *
	 * @return a peekable {@link FluxionTap}
	 */
	public final FluxionTap<O> tap() {
		return FluxionTap.tap(this);
	}

	/**
	 * @see #sampleFirst(Function)
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/throttlefirst.png" alt="">
	 *
	 * @return a sampled {@link Fluxion} by last item observed when the sampler signals
	 *
	 * @since 2.5
	 */
	public final <U> Fluxion<O> throttleFirst(Function<? super O, ? extends Publisher<U>> throttler) {
		return sampleFirst(throttler);
	}

	/**
	 * @see #sample(Publisher)
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/throttlelast.png" alt="">
	 *
	 * @return a sampled {@link Fluxion} by last item observed when the sampler {@link Publisher} signals
	 *
	 * @since 2.5
	 */
	public final <U> Fluxion<O> throttleLast(Publisher<U> throttler) {
		return sample(throttler);
	}

	/**
	 *
	 * Relay requests of N into N delayed requests of 1 to this {@link Fluxion}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/throttlerequest.png" alt="">
	 *
	 * @param period the period in milliseconds to delay downstream requests of N into N x delayed requests of 1
	 *
	 * @return a timed step-request {@link Fluxion}
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> throttleRequest(final long period) {
		final Timer timer = getTimer();
		Assert.state(timer != null, "Cannot use default timer as no environment has been provided to this " + "Stream");

		return new FluxionThrottleRequest<O>(this, timer, period);
	}

	/**
	 *
	 * Relay requests of N into N delayed requests of 1 to this {@link Fluxion}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/throttlerequest.png" alt="">
	 *
	 * @param period the period to delay downstream requests of N into N x delayed requests of 1
	 *
	 * @return a timed step-request {@link Fluxion}
	 *
	 * @since 2.0
	 */
	public final Fluxion<O> throttleRequest(final Duration period) {
		return throttleRequest(period.toMillis());
	}

	/**
	 * @see #sampleTimeout(Function)
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/throttletimeout.png" alt="">
	 *
	 * @return a sampled {@link Fluxion} by last single item observed before a companion {@link Publisher} emits
	 *
	 * @since 2.5
	 */
	public final <U> Fluxion<O> throttleTimeout(Function<? super O, ? extends Publisher<U>> throttler) {
		return sampleTimeout(throttler);
	}

	/**
	 * Signal a {@link java.util.concurrent.TimeoutException} error in case a per-item period in milliseconds fires
	 * before the next item arrives from this {@link Fluxion}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timeouttime.png" alt="">
	 *
	 * @param timeout the timeout in milliseconds between two signals from this {@link Fluxion}
	 *
	 * @return a per-item expirable {@link Fluxion}
	 *
	 * @since 1.1, 2.0
	 */
	public final Fluxion<O> timeout(long timeout) {
		return timeout(Duration.ofMillis(timeout), null);
	}

	/**
	 * Signal a {@link java.util.concurrent.TimeoutException} in case a per-item period fires before the
	 * next item arrives from this {@link Fluxion}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timeouttime.png" alt="">
	 *
	 * @param timeout the timeout between two signals from this {@link Fluxion}
	 *
	 * @return a per-item expirable {@link Fluxion}
	 *
	 * @since 1.1, 2.0
	 */
	public final Fluxion<O> timeout(Duration timeout) {
		return timeout(timeout, null);
	}

	/**
	 * Switch to a fallback {@link Publisher} in case a per-item period
	 * fires before the next item arrives from this {@link Fluxion}.
	 *
	 * <p> If the given {@link Publisher} is null, signal a {@link java.util.concurrent.TimeoutException}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timeouttimefallback.png" alt="">
	 *
	 * @param timeout the timeout between two signals from this {@link Fluxion}
	 * @param fallback the fallback {@link Publisher} to subscribe when a timeout occurs
	 *
	 * @return a per-item expirable {@link Fluxion} with a fallback {@link Publisher}
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final Fluxion<O> timeout(final Duration timeout, final Publisher<? extends O> fallback) {
		final Timer timer = getTimer();
		Assert.state(timer != null, "Cannot use default timer as no environment has been provided to this " + "Stream");

		final Mono<Long> _timer = Mono.delay(timeout, timer).otherwiseJust(0L);
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
	 * Signal a {@link java.util.concurrent.TimeoutException} in case a first item from this {@link Fluxion} has
	 * not been emitted before the given {@link Publisher} emits.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timeoutfirst.png" alt="">
	 *
	 * @param firstTimeout the timeout {@link Publisher} that must not emit before the first signal from this {@link Fluxion}
	 *
	 * @return an expirable {@link Fluxion} if the first item does not come before a {@link Publisher} signal
	 *
	 * @since 2.5
	 */
	public final <U> Fluxion<O> timeout(final Publisher<U> firstTimeout) {
		return timeout(firstTimeout, new Function<O, Publisher<U>>() {
			@Override
			public Publisher<U> apply(O o) {
				return never();
			}
		});
	}

	/**
	 * Signal a {@link java.util.concurrent.TimeoutException} in case a first item from this {@link Fluxion} has
	 * not been emitted before the given {@link Publisher} emits. The following items will be individually timed via
	 * the factory provided {@link Publisher}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timeoutall.png" alt="">
	 *
	 * @param firstTimeout the timeout {@link Publisher} that must not emit before the first signal from this {@link Fluxion}
	 * @param nextTimeoutFactory the timeout {@link Publisher} factory for each next item
	 *
	 * @return a first then per-item expirable {@link Fluxion}
	 *
	 * @since 2.5
	 */
	public final <U, V> Fluxion<O> timeout(Publisher<U> firstTimeout,
			Function<? super O, ? extends Publisher<V>> nextTimeoutFactory) {
		return new FluxionTimeout<>(this, firstTimeout, nextTimeoutFactory);
	}

	/**
	 * Switch to a fallback {@link Publisher} in case a first item from this {@link Fluxion} has
	 * not been emitted before the given {@link Publisher} emits. The following items will be individually timed via
	 * the factory provided {@link Publisher}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timeoutallfallback.png" alt="">
	 *
	 * @param firstTimeout the timeout {@link Publisher} that must not emit before the first signal from this {@link Fluxion}
	 * @param nextTimeoutFactory the timeout {@link Publisher} factory for each next item
	 * @param fallback the fallback {@link Publisher} to subscribe when a timeout occurs
	 *
	 * @return a first then per-item expirable {@link Fluxion} with a fallback {@link Publisher}
	 *
	 * @since 2.5
	 */
	public final <U, V> Fluxion<O> timeout(Publisher<U> firstTimeout,
			Function<? super O, ? extends Publisher<V>> nextTimeoutFactory, final Publisher<? extends O>
			fallback) {
		return new FluxionTimeout<>(this, firstTimeout, nextTimeoutFactory, fallback);
	}

	/**
	 * Emit a {@link reactor.core.tuple.Tuple2} pair of T1 {@link Long} current system time in
	 * millis and T2 {@link <T>} associated data for each item from this {@link Fluxion}
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timestamp.png" alt="">
	 *
	 * @return a timestamped {@link Fluxion}
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final Fluxion<Tuple2<Long, O>> timestamp() {
		return map(TIMESTAMP_OPERATOR);
	}

	/**
	 * Transform this {@link Fluxion} into a lazy {@link Iterable} blocking on next calls.
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
	 * Transform this {@link Fluxion} into a lazy {@link Iterable} blocking on next calls.
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
	 * Transform this {@link Fluxion} into a lazy {@link Iterable} blocking on next calls.
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
	 * Transform this {@link Fluxion} into a lazy {@link Iterable#iterator()} blocking on next calls using a prefetch
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
	 * Accumulate this {@link Fluxion} sequence in a {@link List} that is emitted to the returned {@link Mono} on
	 * onComplete.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tolist.png" alt="">
	 *
	 * @return a {@link Mono} of all values from this {@link Fluxion}
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
	 * {@link Fluxion} sequence into a hashed map where the key is extracted by the given {@link Function} and the
	 * value will be the most recent emitted item for this key.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tomap.png" alt="">
	 *
	 * @param keyExtractor a {@link Function} to route items into a keyed {@link Collection}
	 *
	 * @return a {@link Mono} of all last matched key-values from this {@link Fluxion}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public final <K> Mono<Map<K, O>> toMap(Function<? super O, ? extends K> keyExtractor) {
		return toMap(keyExtractor, Function.identity());
	}

	/**
	 * Convert all this {@link Fluxion} sequence into a hashed map where the key is extracted by the given function and the value will be
	 * the most recent extracted item for this key.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tomap.png" alt="">
	 *
	 * @param keyExtractor a {@link Function} to route items into a keyed {@link Collection}
	 * @param valueExtractor a {@link Function} to select the data to store from each item
	 *
	 * @return a {@link Mono} of all last matched key-values from this {@link Fluxion}
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
	 * Convert all this {@link Fluxion} sequence into a supplied map where the key is extracted by the given function and the value will
	 * be the most recent extracted item for this key.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tomap.png" alt="">
	 *
	 * @param keyExtractor a {@link Function} to route items into a keyed {@link Collection}
	 * @param valueExtractor a {@link Function} to select the data to store from each item
	 * @param mapSupplier a {@link Map} factory called for each {@link Subscriber}
	 *
	 * @return a {@link Mono} of all last matched key-values from this {@link Fluxion}
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
	 * Convert this {@link Fluxion} sequence into a hashed map where the key is extracted by the given function and the value will be
	 * all the emitted item for this key.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tomultimap.png" alt="">
	 *
	 * @param keyExtractor a {@link Function} to route items into a keyed {@link Collection}
	 *
	 * @return a {@link Mono} of all matched key-values from this {@link Fluxion}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public final <K> Mono<Map<K, Collection<O>>> toMultimap(Function<? super O, ? extends K> keyExtractor) {
		return toMultimap(keyExtractor, Function.identity());
	}

	/**
	 * Convert this {@link Fluxion} sequence into a hashed map where the key is extracted by the given function and the value will be
	 * all the extracted items for this key.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tomultimap.png" alt="">
	 *
	 * @param keyExtractor a {@link Function} to route items into a keyed {@link Collection}
	 * @param valueExtractor a {@link Function} to select the data to store from each item
	 *
	 * @return a {@link Mono} of all matched key-values from this {@link Fluxion}
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
	 * Convert this {@link Fluxion} sequence into a supplied map where the key is extracted by the given function and the value will
	 * be all the extracted items for this key.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tomultimap.png" alt="">
	 *
	 * @param keyExtractor a {@link Function} to route items into a keyed {@link Collection}
	 * @param valueExtractor a {@link Function} to select the data to store from each item
	 * @param mapSupplier a {@link Map} factory called for each {@link Subscriber}
	 *
	 * @return a {@link Mono} of all matched key-values from this {@link Fluxion}
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
	 * Hint {@link Subscriber} to this {@link Fluxion} a preferred available capacity should be used.
	 * {@link #toIterable()} can for instance use introspect this value to supply an appropriate queueing strategy.
	 *
	 * @param capacity the maximum capacity (in flight onNext) the return {@link Publisher} should expose
	 *
	 * @return a bounded {@link Fluxion}
	 */
	public Fluxion<O> useCapacity(final long capacity) {
		if (capacity == getCapacity()) {
			return this;
		}
		return FluxionConfig.withCapacity(this, capacity);
	}

	/**
	 * Configure an arbitrary name for later introspection.
	 *
	 * @param name arbitrary {@link Fluxion} name
	 *
	 * @return a configured fluxion
	 */
	public Fluxion<O> useName(final String name) {
		return FluxionConfig.withName(this, name);

	}

	/**
	 * Configure a hinted capacity {@literal Long.MAX_VALUE} that can be used by downstream operators to adapt a
	 * better consuming strage.
	 *
	 * @return {@link Fluxion} with capacity set to max
	 *
	 * @see #useCapacity(long)
	 */
	public final Fluxion<O> useNoCapacity() {
		return useCapacity(Long.MAX_VALUE);
	}

	/**
	 * Configure a {@link Timer} that can be used by timed operators downstream.
	 *
	 * @param timer the timer
	 *
	 * @return a configured fluxion
	 */
	public Fluxion<O> useTimer(final Timer timer) {
		return FluxionConfig.withTimer(this, timer);

	}

	/**
	 * Split this {@link Fluxion} sequence into multiple {@link Fluxion} delimited by the given {@code maxSize}
	 * count and starting from
	 * the first item.
	 * Each {@link Fluxion} bucket will onComplete after {@code maxSize} items have been routed.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsize.png" alt="">
	 *
	 * @param maxSize the maximum routed items before emitting onComplete per {@link Fluxion} bucket
	 *
	 * @return a windowing {@link Fluxion} of sized {@link Fluxion} buckets
	 *
	 * @since 2.0
	 */
	public final Fluxion<Fluxion<O>> window(final int maxSize) {
		return new FluxionWindow<>(this, maxSize, QueueSupplier.<O>get(maxSize));
	}

	/**
	 * Split this {@link Fluxion} sequence into multiple {@link Fluxion} delimited by the given {@code skip}
	 * count,
	 * starting from
	 * the first item.
	 * Each {@link Fluxion} bucket will onComplete after {@code maxSize} items have been routed.
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
	 * @param maxSize the maximum routed items per {@link Fluxion}
	 * @param skip the number of items to count before emitting a new bucket {@link Fluxion}
	 *
	 * @return a windowing {@link Fluxion} of sized {@link Fluxion} buckets every skip count
	 */
	public final Fluxion<Fluxion<O>> window(final int maxSize, final int skip) {
		return new FluxionWindow<>(this,
				maxSize,
				skip,
				QueueSupplier.<O>xs(),
				QueueSupplier.<UnicastProcessor<O>>xs());
	}

	/**
	 * Split this {@link Fluxion} sequence into continuous, non-overlapping windows
	 * where the window boundary is signalled by another {@link Publisher}
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowboundary.png" alt="">
	 *
	 * @param boundary a {@link Publisher} to emit any item for a split signal and complete to terminate
	 *
	 * @return a windowing {@link Fluxion} delimiting its sub-sequences by a given {@link Publisher}
	 */
	public final Fluxion<Fluxion<O>> window(final Publisher<?> boundary) {
		return new FluxionWindowBoundary<>(this,
				boundary,
				QueueSupplier.<O>unbounded(PlatformDependent.XS_BUFFER_SIZE),
				QueueSupplier.unbounded(PlatformDependent.XS_BUFFER_SIZE));
	}

	/**
	 * Split this {@link Fluxion} sequence into potentially overlapping windows controlled by items of a
	 * start {@link Publisher} and end {@link Publisher} derived from the start values.
	 *
	 * <p>
	 * When Open signal is strictly not overlapping Close signal : dropping windows
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowopenclose.png" alt="">
	 * <p>
	 * When Open signal is strictly more frequent than Close signal : overlapping windows
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowopencloseover.png" alt="">
	 * <p>
	 * When Open signal is exactly coordinated with Close signal : exact windows
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowboundary.png" alt="">
	 *
	 * @param bucketOpening a {@link Publisher} to emit any item for a split signal and complete to terminate
	 * @param closeSelector a {@link Function} given an opening signal and returning a {@link Publisher} that
	 * emits to complete the window
	 *
	 * @return a windowing {@link Fluxion} delimiting its sub-sequences by a given {@link Publisher} and lasting until
	 * a selected {@link Publisher} emits
	 */
	public final <U, V> Fluxion<Fluxion<O>> window(final Publisher<U> bucketOpening,
			final Function<? super U, ? extends Publisher<V>> closeSelector) {

		long c = getCapacity();
		c = c == -1L ? Long.MAX_VALUE : c;
		/*if(c > 1 && c < 10_000_000){
			return new StreamWindowBeginEnd<>(this,
					bucketOpening,
					boundarySupplier,
					QueueSupplier.get(c),
					(int)c);
		}*/

		return new FluxionWindowStartEnd<>(this,
				bucketOpening,
				closeSelector,
				QueueSupplier.unbounded(PlatformDependent.XS_BUFFER_SIZE),
				QueueSupplier.<O>unbounded(PlatformDependent.XS_BUFFER_SIZE));
	}

	/**
	 * Split this {@link Fluxion} sequence into continuous, non-overlapping windows delimited by a given period.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowtimespan.png" alt="">
	 *
	 * @param timespan the duration in milliseconds to delimit {@link Fluxion} windows
	 *
	 * @return a windowing {@link Fluxion} of timed {@link Fluxion} buckets
	 *
	 * @since 2.0
	 */
	public final Fluxion<Fluxion<O>> window(long timespan) {
		Timer t = getTimer();
		if(t == null) t = Timer.global();
		return window(interval(timespan, t));
	}

	/**
	 * Split this {@link Fluxion} sequence into continuous, non-overlapping windows delimited by a given period.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowtimespan.png" alt="">
	 *
	 * @param timespan the duration to delimit {@link Fluxion} windows
	 *
	 * @return a windowing {@link Fluxion} of timed {@link Fluxion} buckets
	 *
	 * @since 2.0
	 */
	public final Fluxion<Fluxion<O>> window(Duration timespan) {
		return window(timespan.toMillis());
	}

	/**
	 * Split this {@link Fluxion} sequence into multiple {@link Fluxion} delimited by the given {@code timeshift}
	 * period, starting from the first item.
	 * Each {@link Fluxion} bucket will onComplete after {@code timespan} period has elpased.
	 *
	 * <p>
	 * When timeshift > timespan : dropping windows
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsizeskip.png" alt="">
	 * <p>
	 * When timeshift < timespan : overlapping windows
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsizeskipover.png" alt="">
	 * <p>
	 * When timeshift == timespan : exact windows
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsize.png" alt="">
	 *
	 * @param timespan the maximum {@link Fluxion} window duration in milliseconds
	 * @param timeshift the period of time in milliseconds to create new {@link Fluxion} windows
	 *
	 * @return a windowing
	 * {@link Fluxion} of {@link Fluxion} buckets delimited by an opening {@link Publisher} and a selected closing {@link Publisher}
	 *
	 */
	public final Fluxion<Fluxion<O>> window(final long timespan, final long timeshift) {
		if (timeshift == timespan) {
			return window(timespan);
		}

		Timer t = getTimer();
		if(t == null) t = Timer.global();
		final Timer timer = t;

		return window(interval(0L, timeshift, timer), new Function<Long, Publisher<Long>>() {
			@Override
			public Publisher<Long> apply(Long aLong) {
				return Mono.delay(timespan, timer);
			}
		});
	}

	/**
	 * Split this {@link Fluxion} sequence into multiple {@link Fluxion} delimited by the given {@code timeshift}
	 * period, starting from the first item.
	 * Each {@link Fluxion} bucket will onComplete after {@code timespan} period has elpased.
	 *
	 * <p>
	 * When timeshift > timespan : dropping windows
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsizeskip.png" alt="">
	 * <p>
	 * When timeshift < timespan : overlapping windows
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsizeskipover.png" alt="">
	 * <p>
	 * When timeshift == timespan : exact windows
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsize.png" alt="">
	 *
	 * @param timespan the maximum {@link Fluxion} window duration
	 * @param timeshift the period of time to create new {@link Fluxion} windows
	 *
	 * @return a windowing
	 * {@link Fluxion} of {@link Fluxion} buckets delimited by an opening {@link Publisher} and a selected closing {@link Publisher}
	 *
	 */
	public final Fluxion<Fluxion<O>> window(final Duration timespan, final Duration timeshift) {
		return window(timespan.toMillis(), timeshift.toMillis());
	}

	/**
	 * Split this {@link Fluxion} sequence into multiple {@link Fluxion} delimited by the given {@code maxSize} number
	 * of items, starting from the first item. {@link Fluxion} windows will onComplete after a given
	 * timespan occurs and the number of items has not be counted.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsizetimeout.png" alt="">
	 *
	 * @param maxSize the maximum {@link Fluxion} window items to count before onComplete
	 * @param timespan the timeout to use to onComplete a given window if size is not counted yet
	 *
	 * @return a windowing {@link Fluxion} of sized or timed {@link Fluxion} buckets
	 *
	 * @since 2.0
	 */
	public final Fluxion<Fluxion<O>> window(final int maxSize, final Duration timespan) {
		return new FluxionWindowTimeOrSize<>(this, maxSize, timespan.toMillis(), getTimer());
	}

	/**
	 * Combine values from this {@link Fluxion} with values from another
	 * {@link Publisher} through a {@link BiFunction} and emits the result.
	 * <p>
	 * The operator will drop values from this {@link Fluxion} until the other
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
	 * @return a combined {@link Fluxion} gated by another {@link Publisher}
	 */
	public final <U, R> Fluxion<R> withLatestFrom(Publisher<? extends U> other, BiFunction<? super O, ? super U, ?
			extends R > resultSelector){
		return new FluxionWithLatestFrom<>(this, other, resultSelector);
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
	 * @return a zipped {@link Fluxion}
	 *
	 * @since 2.0
	 */
	public final <T2, V> Fluxion<V> zipWith(final Publisher<? extends T2> source2,
			final BiFunction<? super O, ? super T2, ? extends V> combinator) {
		return FluxionSource.wrap(Flux.zip(this, source2, combinator));
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * produced by the passed combinator from the most recent items emitted by each source until any of them
	 * completes. Errors will immediately be forwarded.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipp.png" alt="">
	 * <p>
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param prefetch the request size to use for this {@link Fluxion} and the other {@link Publisher}
	 * @param <T2> type of the value from source2
	 * @param <V> The produced output after transformation by the combinator
	 *
	 * @return a zipped {@link Fluxion}
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final <T2, V> Fluxion<V> zipWith(final Publisher<? extends T2> source2,
			final BiFunction<? super O, ? super T2, ? extends V> combinator, int prefetch) {
		return zip(objects -> combinator.apply((O)objects[0], (T2)objects[1]), prefetch, this, source2);
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
	 * @return a zipped {@link Fluxion}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public final <T2> Fluxion<Tuple2<O, T2>> zipWith(final Publisher<? extends T2> source2) {
		return FluxionSource.wrap(Flux.<O, T2, Tuple2<O, T2>>zip(this, source2, TUPLE2_BIFUNCTION));
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipp.png" alt="">
	 * <p>
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param prefetch the request size to use for this {@link Fluxion} and the other {@link Publisher}
	 * @param <T2> type of the value from source2
	 *
	 * @return a zipped {@link Fluxion}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public final <T2> Fluxion<Tuple2<O, T2>> zipWith(final Publisher<? extends T2> source2, int prefetch) {
		return zip(Tuple.fn2(), prefetch, this, source2);
	}

	/**
	 * Pairwise combines as {@link Tuple2} elements of this {@link Fluxion} and an {@link Iterable} sequence.
	 *
	 * @param iterable the {@link Iterable} to pair with
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipwithiterable.png" alt="">
	 *
	 * @return a zipped {@link Fluxion}
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public final <T2> Fluxion<Tuple2<O, T2>> zipWithIterable(Iterable<? extends T2> iterable) {
		return new FluxionZipIterable<>(this, iterable, (BiFunction<O, T2, Tuple2<O, T2>>)TUPLE2_BIFUNCTION);
	}

	/**
	 * Pairwise combines elements of this
	 * {@link Fluxion} and an {@link Iterable} sequence using the given zipper {@link BiFunction}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipwithiterable.png" alt="">
	 *
	 * @param iterable the {@link Iterable} to pair with
	 * @param zipper the {@link BiFunction} combinator
	 *
	 * @return a zipped {@link Fluxion}
	 *
	 * @since 2.5
	 */
	public final <T2, V> Fluxion<V> zipWithIterable(Iterable<? extends T2> iterable,
			BiFunction<? super O, ? super T2, ? extends V> zipper) {
		return new FluxionZipIterable<>(this, iterable, zipper);
	}

	static final BiFunction      JOIN_BIFUNCTION         = (t1, t2) -> Arrays.asList(t1, t2);
	static final BooleanSupplier ALWAYS_BOOLEAN_SUPPLIER = () -> true;
	static final Function        HASHCODE_EXTRACTOR      = Object::hashCode;
	static final Supplier        LIST_SUPPLIER           = ArrayList::new;
	static final Supplier        SET_SUPPLIER            = HashSet::new;
	static final Function        TIMESTAMP_OPERATOR      = o -> Tuple.of(System.currentTimeMillis(), o);
	static final Fluxion         NEVER                   = from(Flux.never());
	static final BiFunction      TUPLE2_BIFUNCTION       = Tuple::of;

	@SuppressWarnings("unchecked")
	static final Function        JOIN_FUNCTION           =  objects -> Arrays.asList((Object[])objects);

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