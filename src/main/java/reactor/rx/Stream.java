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
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.Flux;
import reactor.Mono;
import reactor.Processors;
import reactor.Subscribers;
import reactor.Timers;
import reactor.core.error.Exceptions;
import reactor.core.processor.ProcessorGroup;
import reactor.core.publisher.FluxDefaultIfEmpty;
import reactor.core.publisher.FluxFlatMap;
import reactor.core.publisher.FluxLog;
import reactor.core.publisher.FluxMapSignal;
import reactor.core.publisher.FluxResume;
import reactor.core.publisher.FluxZip;
import reactor.core.publisher.MonoIgnoreElements;
import reactor.core.publisher.MonoNext;
import reactor.core.subscriber.BaseSubscriber;
import reactor.core.subscriber.BlockingIterable;
import reactor.core.subscriber.SubscriberWithContext;
import reactor.core.subscription.EmptySubscription;
import reactor.core.subscription.ReactiveSession;
import reactor.core.support.Assert;
import reactor.core.support.QueueSupplier;
import reactor.core.support.ReactiveState;
import reactor.core.support.ReactiveStateUtils;
import reactor.core.timer.Timer;
import reactor.fn.BiConsumer;
import reactor.fn.BiFunction;
import reactor.fn.BooleanSupplier;
import reactor.fn.Consumer;
import reactor.fn.Function;
import reactor.fn.Predicate;
import reactor.fn.Supplier;
import reactor.fn.tuple.Tuple;
import reactor.fn.tuple.Tuple2;
import reactor.fn.tuple.Tuple3;
import reactor.fn.tuple.Tuple4;
import reactor.fn.tuple.Tuple5;
import reactor.fn.tuple.Tuple6;
import reactor.fn.tuple.Tuple7;
import reactor.rx.broadcast.Broadcaster;
import reactor.rx.broadcast.StreamProcessor;
import reactor.rx.stream.GroupedStream;
import reactor.rx.stream.MonoAll;
import reactor.rx.stream.MonoAny;
import reactor.rx.stream.MonoCollect;
import reactor.rx.stream.MonoCount;
import reactor.rx.stream.MonoElementAt;
import reactor.rx.stream.MonoIsEmpty;
import reactor.rx.stream.MonoReduce;
import reactor.rx.stream.MonoSingle;
import reactor.rx.stream.Signal;
import reactor.rx.stream.StreamAccumulate;
import reactor.rx.stream.StreamBarrier;
import reactor.rx.stream.StreamBatch;
import reactor.rx.stream.StreamBuffer;
import reactor.rx.stream.StreamBufferBoundary;
import reactor.rx.stream.StreamBufferShiftTimeout;
import reactor.rx.stream.StreamBufferStartEnd;
import reactor.rx.stream.StreamBufferTimeout;
import reactor.rx.stream.StreamCallback;
import reactor.rx.stream.StreamCombineLatest;
import reactor.rx.stream.StreamConcatArray;
import reactor.rx.stream.StreamConcatIterable;
import reactor.rx.stream.StreamDebounce;
import reactor.rx.stream.StreamDefer;
import reactor.rx.stream.StreamDelaySubscription;
import reactor.rx.stream.StreamDematerialize;
import reactor.rx.stream.StreamDistinct;
import reactor.rx.stream.StreamDistinctUntilChanged;
import reactor.rx.stream.StreamDrop;
import reactor.rx.stream.StreamElapsed;
import reactor.rx.stream.StreamError;
import reactor.rx.stream.StreamErrorWithValue;
import reactor.rx.stream.StreamFilter;
import reactor.rx.stream.StreamFinally;
import reactor.rx.stream.StreamFuture;
import reactor.rx.stream.StreamGroupBy;
import reactor.rx.stream.StreamIterable;
import reactor.rx.stream.StreamJust;
import reactor.rx.stream.StreamKv;
import reactor.rx.stream.StreamLatest;
import reactor.rx.stream.StreamMap;
import reactor.rx.stream.StreamMaterialize;
import reactor.rx.stream.StreamRange;
import reactor.rx.stream.StreamReduceByKey;
import reactor.rx.stream.StreamRepeat;
import reactor.rx.stream.StreamRepeatPredicate;
import reactor.rx.stream.StreamRepeatWhen;
import reactor.rx.stream.StreamRetry;
import reactor.rx.stream.StreamRetryPredicate;
import reactor.rx.stream.StreamRetryWhen;
import reactor.rx.stream.StreamSample;
import reactor.rx.stream.StreamScan;
import reactor.rx.stream.StreamScanByKey;
import reactor.rx.stream.StreamSkip;
import reactor.rx.stream.StreamSkipLast;
import reactor.rx.stream.StreamSkipUntil;
import reactor.rx.stream.StreamSkipWhile;
import reactor.rx.stream.StreamSort;
import reactor.rx.stream.StreamStateCallback;
import reactor.rx.stream.StreamSwitchIfEmpty;
import reactor.rx.stream.StreamSwitchMap;
import reactor.rx.stream.StreamTake;
import reactor.rx.stream.StreamTakeLast;
import reactor.rx.stream.StreamTakeUntil;
import reactor.rx.stream.StreamTakeUntilPredicate;
import reactor.rx.stream.StreamTakeWhile;
import reactor.rx.stream.StreamThrottleRequest;
import reactor.rx.stream.StreamThrottleRequestWhen;
import reactor.rx.stream.StreamTimeout;
import reactor.rx.stream.StreamTimerPeriod;
import reactor.rx.stream.StreamTimerSingle;
import reactor.rx.stream.StreamUsing;
import reactor.rx.stream.StreamWindow;
import reactor.rx.stream.StreamWindowShift;
import reactor.rx.stream.StreamWindowShiftWhen;
import reactor.rx.stream.StreamWindowWhen;
import reactor.rx.stream.StreamWithLatestFrom;
import reactor.rx.stream.StreamZipIterable;
import reactor.rx.subscriber.AdaptiveSubscriber;
import reactor.rx.subscriber.BlockingQueueSubscriber;
import reactor.rx.subscriber.BoundedSubscriber;
import reactor.rx.subscriber.Control;
import reactor.rx.subscriber.InterruptableSubscriber;
import reactor.rx.subscriber.ManualSubscriber;
import reactor.rx.subscriber.Tap;

/**
 * A public factory to build {@link Stream}, Streams provide for common transformations from a few structures such as
 * Iterable or Future to a Stream, in addition to provide for combinatory operations (merge, switchOnNext...).
 * <p>
 * <p>
 * Examples of use (In Java8 but would also work with Anonymous classes or Groovy Closures for instance):
 * <pre>
 * {@code
 * Stream.just(1, 2, 3).map(i -> i*2) //...
 *
 * Broadcaster<String> stream = Stream.broadcast()
 * strean.map(i -> i*2).consume(System.out::println);
 * stream.onNext("hello");
 *
 * Stream.yield( subscriber -> {
 *   subscriber.onNext(1);
 *   subscriber.onNext(2);
 *   subscriber.onNext(3);
 *   subscriber.onComplete();
 * }).consume(System.out::println);
 *
 * Broadcaster<Integer> inputStream1 = Broadcaster.create();
 * Broadcaster<Integer> inputStream2 = Broadcaster.create();
 * inputStream1.mergeWith(inputStream2).map(i -> i*2).consume(System.out::println);
 *
 * inputStream1.onNext(1);
 * inputStream2.onNext(2);
 *
 * }
 * </pre>
 *
 * @param <O> The type of the output values
 *
 * @author Stephane Maldini
 * @since 1.1, 2.0, 2.5
 */
public abstract class Stream<O> implements Publisher<O>, ReactiveState.Bounded {

	/**
	 * Build a Synchronous {@literal Stream} whose data are generated by the passed publishers.

	 *
	 * @param mergedPublishers The list of upstream {@link Publisher} to subscribe to.
	 * @param <T>              type of the value
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T> Stream<T> amb(Iterable<? extends Publisher<? extends T>> mergedPublishers) {
		return from(Flux.amb(mergedPublishers));
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the passed publishers.

	 *
	 * @param sources The upstreams {@link Publisher} to subscribe to.
	 * @param <T>     type of the value
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0, 2.5
	 */
	@SafeVarargs
	@SuppressWarnings({"unchecked", "varargs"})
	public static <T> Stream<T> amb(Publisher<? extends T>... sources) {
		return from(Flux.amb(sources));
	}

	/**
	 * Wait 30 Seconds until a terminal signal from the passed publisher has been emitted.
	 * If the terminal signal is an error, it will propagate to the caller.
	 * Effectively this is making sure a stream has completed before the return of this call.
	 * It is usually used in controlled environment such as tests.
	 *
	 * @param publisher the publisher to listen for terminal signals
	 */
	public static void await(Publisher<?> publisher) throws InterruptedException {
		await(publisher, 30000, TimeUnit.MILLISECONDS);
	}

	/**
	 * Wait {code timeout} in {@code unit} until a terminal signal from the passed publisher has been emitted.
	 * If the terminal signal is an error, it will propagate to the caller.
	 * Effectively this is making sure a stream has completed before the return of this call.
	 * It is usually used in controlled environment such as tests.
	 *
	 * @param publisher the publisher to listen for terminal signals
	 * @param timeout   the maximum wait time in unit
	 * @param unit      the TimeUnit to use for the timeout
	 */
	public static void await(Publisher<?> publisher, long timeout, TimeUnit unit) throws InterruptedException {
		final AtomicReference<Throwable> exception = new AtomicReference<>();

		final CountDownLatch latch = new CountDownLatch(1);
		publisher.subscribe(new BaseSubscriber<Object>() {
			Subscription s;

			@Override
			public void onComplete() {
				s = null;
				latch.countDown();
			}

			@Override
			public void onError(Throwable throwable) {
				s = null;
				exception.set(throwable);
				latch.countDown();
			}

			@Override
			public void onSubscribe(Subscription subscription) {
				s = subscription;
				subscription.request(Long.MAX_VALUE);
			}
		});

		latch.await(timeout, unit);
		if (exception.get() != null) {
			InterruptedException ie = new InterruptedException();
			Exceptions.addCause(ie, exception.get());
			throw ie;
		}
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the combination of the most recent published values from
	 * all publishers.
	 *
	 * @param sources    The upstreams {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <T>       type of the value from sources
	 * @param <V>        The produced output after transformation by {@param combinator}
	 * @return a {@link Stream} based on the produced value
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
				(Supplier<? extends Queue<StreamCombineLatest.SourceAndArray>>) XS_QUEUE_SUPPLIER,
				ReactiveState.XS_BUFFER_SIZE);
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the combination of the most recent published values from
	 * all publishers.
	 *
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <V> The produced output after transformation by {@param combinator}
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
	 * Build a {@literal Stream} whose data are generated by the combination of the most recent published values from
	 * all publishers.

	 *
	 * @param source1    The first upstream {@link Publisher} to subscribe to.
	 * @param source2    The second upstream {@link Publisher} to subscribe to.
	 * @param source3    The third upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <T1>       type of the value from source1
	 * @param <T2>       type of the value from source2
	 * @param <T3>       type of the value from source3
	 * @param <V>        The produced output after transformation by {@param combinator}
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
	 * Build a {@literal Stream} whose data are generated by the combination of the most recent published values from
	 * all publishers.

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
	 * @param <V>        The produced output after transformation by {@param combinator}
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
	 * Build a {@literal Stream} whose data are generated by the combination of the most recent published values from
	 * all publishers.

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
	 * @param <V>        The produced output after transformation by {@param combinator}
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
	 * Build a {@literal Stream} whose data are generated by the combination of the most recent published values from
	 * all publishers.

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
	 * @param <V>        The produced output after transformation by {@param combinator}
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
	 * Build a {@literal Stream} whose data are generated by the combination of the most recent published values from
	 * all publishers.

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
	 * @param <V>        The produced output after transformation by {@param combinator}
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
	 * Build a {@literal Stream} whose data are generated by the combination of the most recent published values from
	 * all publishers.

	 *
	 * @param sources    The list of upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <V>        The produced output after transformation by {@param combinator}
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
				combinator,
				(Supplier<? extends Queue<StreamCombineLatest.SourceAndArray>>) XS_QUEUE_SUPPLIER,
				ReactiveState.XS_BUFFER_SIZE
		);
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the combination of the most recent published values from
	 * all publishers.
	 *
	 * @param sources    The publisher of upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <V>        The produced output after transformation by {@param combinator}
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0, 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <V> Stream<V> combineLatest(Publisher<? extends Publisher<?>> sources,
			final Function<Object[], V> combinator) {
		return from(sources).buffer()
		                    .flatMap(new Function<List<? extends Publisher<?>>, Publisher<V>>() {
					@Override
					public Publisher<V> apply(List<? extends Publisher<?>> publishers) {
						return new StreamCombineLatest<>(publishers,
								combinator,
								(Supplier<? extends Queue<StreamCombineLatest.SourceAndArray>>) XS_QUEUE_SUPPLIER,
								ReactiveState.XS_BUFFER_SIZE);
					}
		                    }
		);
	}

	/**
	 * Build a Synchronous {@literal Stream} whose data are generated by the passed publishers.
	 * Each source publisher will be consumed until complete in sequence, with the same order than they have been
	 * passed
	 * to.
	 *
	 * @param mergedPublishers The list of upstream {@link Publisher} to subscribe to.
	 * @param <T>              type of the value
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T> Stream<T> concat(Iterable<? extends Publisher<? extends T>> mergedPublishers) {
		return new StreamConcatIterable<>(mergedPublishers);
	}

	/**
	 * Build a Synchronous {@literal Stream} whose data are generated by the passed publishers.
	 * Each source publisher will be consumed until complete in sequence, with the same order than they have been
	 * passed
	 * to.
	 *
	 * @param concatdPublishers The publisher of upstream {@link Publisher} to subscribe to.
	 * @param <T>               type of the value
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	public static <T> Stream<T> concat(Publisher<? extends Publisher<? extends T>> concatdPublishers) {
		return from(Flux.concat(concatdPublishers));
	}

	/**
	 * Build a Synchronous {@literal Stream} whose data are generated by the passed publishers.
	 * Each source publisher will be consumed until complete in sequence, with the same order than they have been
	 * passed
	 * to.
	 *
	 * @param sources The upstream {@link Publisher} to subscribe to.
	 * @param <T>     type of the value
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0, 2.5
	 */
	@SuppressWarnings("varargs")
	@SafeVarargs
	public static <T> Stream<T> concat(Publisher<? extends T>... sources) {
		return new StreamConcatArray<>(sources);
	}

	/**
	 * @see Flux#convert(Object)
	 * @since 2.5
	 */
	public static <T> Stream<T> convert(Object source) {
		return from(Flux.<T>convert(source));
	}

	/**
	 * @see Flux#create(Consumer)
	 */
	public static <T> Stream<T> create(Consumer<SubscriberWithContext<T, Void>> request) {
		return from(Flux.create(request));
	}

	/**
	 * @see Flux#create(Consumer, Function)
	 */
	public static <T, C> Stream<T> create(Consumer<SubscriberWithContext<T, C>> request,
			Function<Subscriber<? super T>, C> onSubscribe) {
		return from(Flux.create(request, onSubscribe));
	}

	/**
	 * @see Flux#create(Consumer, Function, Consumer)
	 */
	public static <T, C> Stream<T> create(Consumer<SubscriberWithContext<T, C>> request,
			Function<Subscriber<? super T>, C> onSubscribe,
			Consumer<C> onTerminate) {
		return from(Flux.create(request, onSubscribe, onTerminate));
	}

	/**
	 * Create a {@link Stream} reacting on requests with the passed {@link BiConsumer}
	 *
	 * @param requestConsumer A {@link BiConsumer} with left argument request and right argument target subscriber
	 * @param <T>             The type of the data sequence
	 * @return a Stream
	 * @since 2.0.2
	 */
	public static <T> Stream<T> createWith(BiConsumer<Long, SubscriberWithContext<T, Void>> requestConsumer) {
		if (requestConsumer == null) throw new IllegalArgumentException("Supplier must be provided");
		return createWith(requestConsumer, null, null);
	}

	/**
	 * Create a {@link Stream} reacting on requests with the passed {@link BiConsumer}
	 * The argument {@code contextFactory} is executed once by new subscriber to generate a context shared by every
	 * request calls.
	 *
	 * @param requestConsumer A {@link BiConsumer} with left argument request and right argument target subscriber
	 * @param contextFactory  A {@link Function} called for every new subscriber returning an immutable context (IO
	 *                        connection...)
	 * @param <T>             The type of the data sequence
	 * @param <C>             The type of contextual information to be read by the requestConsumer
	 * @return a Stream
	 * @since 2.0.2
	 */
	public static <T, C> Stream<T> createWith(BiConsumer<Long, SubscriberWithContext<T, C>> requestConsumer,
	                                          Function<Subscriber<? super T>, C> contextFactory) {
		return createWith(requestConsumer, contextFactory, null);
	}

	/**
	 * Create a {@link Stream} reacting on requests with the passed {@link BiConsumer}.
	 * The argument {@code contextFactory} is executed once by new subscriber to generate a context shared by every
	 * request calls.
	 * The argument {@code shutdownConsumer} is executed once by subscriber termination event (cancel, onComplete,
	 * onError).
	 *
	 * @param requestConsumer  A {@link BiConsumer} with left argument request and right argument target subscriber
	 * @param contextFactory   A {@link Function} called once for every new subscriber returning an immutable context
	 *                         (IO connection...)
	 * @param shutdownConsumer A {@link Consumer} called once everytime a subscriber terminates: cancel, onComplete(),
	 *                         onError()
	 * @param <T>              The type of the data sequence
	 * @param <C>              The type of contextual information to be read by the requestConsumer
	 * @return a fresh Reactive Streams publisher ready to be subscribed
	 * @since 2.0.2
	 */
	public static <T, C> Stream<T> createWith(BiConsumer<Long, SubscriberWithContext<T, C>> requestConsumer,
	                                          Function<Subscriber<? super T>, C> contextFactory,
	                                          Consumer<C> shutdownConsumer) {
		return from(Flux.generate(requestConsumer, contextFactory, shutdownConsumer));
	}

	/**
	 * Supply a {@link Publisher} everytime subscribe is called on the returned stream. The passed {@link reactor.fn
	 * .Supplier}
	 * will be invoked and it's up to the developer to choose to return a new instance of a {@link Publisher} or reuse
	 * one,
	 * effecitvely behaving like {@link Stream#from(Publisher)}.
	 *
	 * @param supplier the publisher factory to call on subscribe
	 * @param <T>      the type of values passing through the {@literal Stream}
	 * @return a new {@link Stream}
	 */
	public static <T> Stream<T> defer(Supplier<? extends Publisher<T>> supplier) {
		return new StreamDefer<>(supplier);
	}

	/**
	 * Build a {@literal Stream} that will only emit a complete signal to any new subscriber.
	 *
	 * @return a new {@link Stream}
	 */
	@SuppressWarnings("unchecked")
	public static <T> Stream<T> empty() {
		return (Stream<T>) StreamJust.EMPTY;
	}

	/**
	 * Build a {@literal Stream} that will only emit an error signal to any new subscriber.
	 *
	 * @return a new {@link Stream}
	 */
	public static <O, T extends Throwable> Stream<O> fail(T throwable) {
		return from(Mono.<O>error(throwable));
	}

	/**
	 * A simple decoration of the given {@link Publisher} to expose {@link Stream} API and proxy any subscribe call to
	 * the publisher.
	 * The Publisher has to first call onSubscribe and receive a subscription request callback before any onNext
	 * call or
	 * will risk loosing events.
	 *
	 * @param publisher the publisher to decorate the Stream subscriber
	 * @param <T>       the type of values passing through the {@literal Stream}
	 * @return a new {@link Stream}
	 */
	@SuppressWarnings("unchecked")
	public static <T> Stream<T> from(final Publisher<T> publisher) {
		if (Stream.class.isAssignableFrom(publisher.getClass())) {
			return (Stream<T>) publisher;
		}

		if (Supplier.class.isAssignableFrom(publisher.getClass())) {
			T t = ((Supplier<T>)publisher).get();
			if(t != null){
				return just(t);
			}
		}
		return new StreamBarrier.Identity<>(publisher);
	}

	/**
	 * Build a {@literal Stream} whom data is sourced by each element of the passed array on subscription request.
	 * <p>
	 * It will use the passed dispatcher to emit signals.
	 *
	 * @param values The values to {@code onNext()}
	 * @param <T>    type of the values
	 * @return a {@link Stream} based on the given values
	 */
	public static <T> Stream<T> fromArray(T[] values) {
		return from(Flux.fromArray(values));
	}

	/**
	 * Build a {@literal Stream} that will only emit the result of the future and then complete.
	 * The future will be polled for an unbounded amount of time.
	 *
	 * @param future the future to poll value from
	 * @return a new {@link Stream}
	 */
	public static <T> Stream<T> fromFuture(Future<? extends T> future) {
		return StreamFuture.create(future);
	}

	/**
	 * Build a {@literal Stream} that will only emit the result of the future and then complete.
	 * The future will be polled for an unbounded amount of time.
	 *
	 * @param future the future to poll value from
	 * @return a new {@link Stream}
	 */
	public static <T> Stream<T> fromFuture(Future<? extends T> future, long time, TimeUnit unit) {
		return StreamFuture.create(future, time, unit);
	}

	/**
	 * Build a {@literal Stream} whom data is sourced by each element of the passed iterable on subscription request.
	 * <p>
	 * It will use the passed dispatcher to emit signals.
	 *
	 * @param values The values to {@code onNext()}
	 * @param <T>    type of the values
	 * @return a {@link Stream} based on the given values
	 */
	public static <T> Stream<T> fromIterable(Iterable<? extends T> values) {
		return new StreamIterable<>(values);
	}

	/**
	 * Build a {@literal Stream} whom data is sourced by each element of the passed iterator on subscription request.
	 * <p>
	 * It will use the passed dispatcher to emit signals.
	 *
	 * @param values The values to {@code onNext()}
	 * @param <T>    type of the values
	 * @return a {@link Stream} based on the given values
	 */
	public static <T> Stream<T> fromIterator(Iterator<? extends T> values) {
		return from(Flux.fromIterator(values));
	}

	/**
	 * A simple decoration of the given {@link Processor} to expose {@link Stream} API and proxy any subscribe call to
	 * the Processor.
	 * The Processor has to first call onSubscribe and receive a subscription request callback before any onNext
	 * call or
	 * will risk loosing events.
	 *
	 * @param processor the processor to decorate with the Stream API
	 * @param <I>       the type of values observed by the receiving subscriber
	 * @param <O>       the type of values passing through the sending {@literal Stream}
	 * @return a new {@link Stream}
	 */
	@SuppressWarnings("unchecked")
	public static <I, O> StreamProcessor<I, O> fromProcessor(final Processor<I, O> processor) {
		if (StreamProcessor.class.isAssignableFrom(processor.getClass())) {
			return (StreamProcessor<I, O>) processor;
		}
		return StreamProcessor.from(processor);
	}

	/**
	 * Build a Synchronous {@literal Stream} whose data are aggregated from the passed publishers
	 * (1 element consumed for each merged publisher. resulting in an array of size of {@param mergedPublishers}.

	 *
	 * @param sources The upstreams {@link Publisher} to subscribe to.
	 * @param <T>     type of the value
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	@SuppressWarnings({"unchecked", "varargs"})
	@SafeVarargs
	public static <T> Stream<List<T>> join(Publisher<? extends T>... sources) {
		return from(Flux.zip(FluxZip.JOIN_FUNCTION, sources));
	}

	/**
	 * Build a Synchronous {@literal Stream} whose data are aggregated from the passed publishers
	 * (1 element consumed for each merged publisher. resulting in an array of size of {@param mergedPublishers}.

	 *
	 * @param sources The list of upstream {@link Publisher} to subscribe to.
	 * @param <T>     type of the value
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T> Stream<List<T>> join(Iterable<? extends Publisher<?>> sources) {
		return zip(sources, FluxZip.JOIN_FUNCTION);
	}

	/**
	 * Build a {@literal Stream} whom data is sourced by the passed element on subscription
	 * request. After all data is being dispatched, a complete signal will be emitted.
	 * <p>
	 *
	 * @param value1 The only value to {@code onNext()}
	 * @param <T>    type of the values
	 * @return a {@link Stream} based on the given values
	 */
	public static <T> Stream<T> just(T value1) {
		if(value1 == null){
			throw new Exceptions.Spec213_ArgumentIsNull();
		}

		return new StreamJust<T>(value1);
	}

	/**
	 * Build a {@literal Stream} whom data is sourced by each element of the passed iterable on subscription
	 * request.
	 * <p>
	 *
	 * @param values The values to {@code onNext()}
	 * @param <T>    type of the values
	 * @return a {@link Stream} based on the given values
	 */
	@SafeVarargs
	@SuppressWarnings({"unchecked", "varargs"})
	public static <T> Stream<T> just(T... values) {
		return from(Flux.fromArray(Objects.requireNonNull(values)));
	}

	/**
	 *
	 * @param publisher
	 * @param operator
	 * @param <I>
	 * @param <O>
	 * @return
	 */
	public static <I, O> Stream<O> lift(final Publisher<I> publisher,
			Function<Subscriber<? super O>, Subscriber<? super I>> operator) {
		return new StreamBarrier.Operator<>(publisher, operator);
	}

	/**
	 * Build a Synchronous {@literal Stream} whose data are generated by the passed publishers.

	 *
	 * @param mergedPublishers The list of upstream {@link Publisher} to subscribe to.
	 * @param <T>              type of the value
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T> Stream<T> merge(Iterable<? extends Publisher<? extends T>> mergedPublishers) {
		return from(Flux.merge(mergedPublishers));
	}

	/**
	 * Build a Synchronous {@literal Stream} whose data are generated by the passed publishers.

	 *
	 * @param mergedPublishers The publisher of upstream {@link Publisher} to subscribe to.
	 * @param <T>              type of the value
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	public static <T, E extends T> Stream<E> merge(Publisher<? extends Publisher<E>> mergedPublishers) {
		return from(Flux.merge(mergedPublishers));
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the passed publishers.

	 *
	 * @param sources The upstreams {@link Publisher} to subscribe to.
	 * @param <T>     type of the value
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0, 2.5
	 */
	@SafeVarargs
	@SuppressWarnings({"unchecked", "varargs"})
	public static <T> Stream<T> merge(Publisher<? extends T>... sources) {
		return from(Flux.merge(sources));
	}

	/**
	 * Build a {@literal Stream} that will never emit anything.
	 *
	 * @return a new {@link Stream}
	 */
	@SuppressWarnings("unchecked")
	public static <T> Stream<T> never() {
		return (Stream<T>) NEVER;
	}

	/**
	 * Build a {@literal Stream} that will emit ever increasing counter from 0 after on each period from the subscribe
	 * call.
	 * It will never complete until cancelled.
	 *
	 * @param period the period in SECONDS before each following increment
	 * @return a new {@link Stream}
	 */
	public static Stream<Long> period(long period) {
		return period(Timers.globalOrNew(), -1l, period, TimeUnit.SECONDS);
	}

	/**
	 * Build a {@literal Stream} that will emit ever increasing counter from 0 after on each period from the subscribe
	 * call.
	 * It will never complete until cancelled.
	 *
	 * @param timer  the timer to run on
	 * @param period the period in SECONDS before each following increment
	 * @return a new {@link Stream}
	 */
	public static Stream<Long> period(Timer timer, long period) {
		return period(timer, -1l, period, TimeUnit.SECONDS);
	}

	/**
	 * Build a {@literal Stream} that will emit ever increasing counter from 0 after the time delay on each period.
	 * It will never complete until cancelled.
	 *
	 * @param delay  the timespan in SECONDS to wait before emitting 0l
	 * @param period the period in SECONDS before each following increment
	 * @return a new {@link Stream}
	 */
	public static Stream<Long> period(long delay, long period) {
		return period(Timers.globalOrNew(), delay, period, TimeUnit.SECONDS);
	}

	/**
	 * Build a {@literal Stream} that will emit ever increasing counter from 0 after the time delay on each period.
	 * It will never complete until cancelled.
	 *
	 * @param timer  the timer to run on
	 * @param delay  the timespan in SECONDS to wait before emitting 0l
	 * @param period the period in SECONDS before each following increment
	 * @return a new {@link Stream}
	 */
	public static Stream<Long> period(Timer timer, long delay, long period) {
		return period(timer, delay, period, TimeUnit.SECONDS);
	}

	/**
	 * Build a {@literal Stream} that will emit ever increasing counter from 0 after the subscribe call on each period.
	 * It will never complete until cancelled.
	 *
	 * @param period the period in [unit] before each following increment
	 * @param unit   the time unit
	 * @return a new {@link Stream}
	 */
	public static Stream<Long> period(long period, TimeUnit unit) {
		return period(Timers.globalOrNew(), -1l, period, unit);
	}

	/**
	 * Build a {@literal Stream} that will emit ever increasing counter from 0 after the subscribe call on each period.
	 * It will never complete until cancelled.
	 *
	 * @param timer  the timer to run on
	 * @param period the period in [unit] before each following increment
	 * @param unit   the time unit
	 * @return a new {@link Stream}
	 */
	public static Stream<Long> period(Timer timer, long period, TimeUnit unit) {
		return period(timer, -1l, period, unit);
	}

	/**
	 * Build a {@literal Stream} that will emit ever increasing counter from 0 after the subscribe call on each period.
	 * It will never complete until cancelled.
	 *
	 * @param delay  the timespan in [unit] to wait before emitting 0l
	 * @param period the period in [unit] before each following increment
	 * @param unit   the time unit
	 * @return a new {@link Stream}
	 */
	public static Stream<Long> period(long delay, long period, TimeUnit unit) {
		return period(Timers.globalOrNew(), delay, period, unit);
	}

	/**
	 * Build a {@literal Stream} that will emit ever increasing counter from 0 after the time delay on each period.
	 * It will never complete until cancelled.
	 *
	 * @param timer  the timer to run on
	 * @param delay  the timespan in [unit] to wait before emitting 0l
	 * @param period the period in [unit] before each following increment
	 * @param unit   the time unit
	 * @return a new {@link Stream}
	 */
	public static Stream<Long> period(Timer timer, long delay, long period, TimeUnit unit) {
		return new StreamTimerPeriod(TimeUnit.MILLISECONDS.convert(delay, unit), period, unit, timer);
	}

	/**
	 * Build a {@literal Stream} that will only emit a sequence of int within the specified range and then
	 * complete.
	 *
	 * @param start the starting value to be emitted
	 * @param count   the number ot times to emit an increment including the first value
	 * @return a new {@link Stream}
	 */
	public static Stream<Integer> range(int start, int count) {
		return new StreamRange(start, count);
	}

	/**
	 * @param publisher
	 * @param accumulator
	 * @param <KEY>
	 * @param <VALUE>
	 * @return
	 */
	public static <KEY, VALUE> Stream<Tuple2<KEY, VALUE>> reduceByKey(Publisher<Tuple2<KEY, VALUE>> publisher,
	                                                                  BiFunction<VALUE, VALUE, VALUE> accumulator) {
		return reduceByKey(publisher, null, null, accumulator);
	}

	/**
	 * @param publisher
	 * @param mapStream
	 * @param accumulator
	 * @param <KEY>
	 * @param <VALUE>
	 * @return
	 */
	public static <KEY, VALUE> Stream<Tuple2<KEY, VALUE>> reduceByKey(Publisher<Tuple2<KEY, VALUE>> publisher,
	                                                                  StreamKv<KEY, VALUE> mapStream,
	                                                                  BiFunction<VALUE, VALUE, VALUE> accumulator) {
		return reduceByKey(publisher, mapStream.getStore(), mapStream, accumulator);
	}

	/**
	 * @param publisher
	 * @param store
	 * @param listener
	 * @param accumulator
	 * @param <KEY>
	 * @param <VALUE>
	 * @return
	 */
	public static <KEY, VALUE> Stream<Tuple2<KEY, VALUE>> reduceByKey(Publisher<Tuple2<KEY, VALUE>> publisher,
	                                                                  Map<KEY, VALUE> store,
	                                                                  Publisher<? extends StreamKv.Signal<KEY,
	                                                                    VALUE>> listener,
	                                                                  BiFunction<VALUE, VALUE, VALUE> accumulator) {
		return reduceByKeyOn(publisher, store, listener, accumulator);
	}

	/**
	 * @param publisher
	 * @param store
	 * @param listener
	 * @param accumulator
	 * @param <KEY>
	 * @param <VALUE>
	 * @return
	 */
	public static <KEY, VALUE> Stream<Tuple2<KEY, VALUE>> reduceByKeyOn(Publisher<Tuple2<KEY, VALUE>> publisher,
	                                                                    Map<KEY, VALUE> store,
	                                                                    Publisher<? extends StreamKv.Signal<KEY,
	                                                                      VALUE>> listener,
	                                                                    BiFunction<VALUE, VALUE, VALUE> accumulator) {
		return new StreamReduceByKey<>(publisher, accumulator, store, listener);
	}

	/**
	 * @param publisher
	 * @param accumulator
	 * @param <KEY>
	 * @param <VALUE>
	 * @return
	 */
	public static <KEY, VALUE> Stream<Tuple2<KEY, VALUE>> scanByKey(Publisher<Tuple2<KEY, VALUE>> publisher,
	                                                                BiFunction<VALUE, VALUE, VALUE> accumulator) {
		return scanByKey(publisher, null, null, accumulator);
	}

	/**
	 * @param publisher
	 * @param mapStream
	 * @param accumulator
	 * @param <KEY>
	 * @param <VALUE>
	 * @return
	 */
	public static <KEY, VALUE> Stream<Tuple2<KEY, VALUE>> scanByKey(Publisher<Tuple2<KEY, VALUE>> publisher,
	                                                                StreamKv<KEY, VALUE> mapStream,
	                                                                BiFunction<VALUE, VALUE, VALUE> accumulator) {
		return scanByKey(publisher, mapStream.getStore(), mapStream, accumulator);
	}

	/**
	 * @param publisher
	 * @param store
	 * @param listener
	 * @param accumulator
	 * @param <KEY>
	 * @param <VALUE>
	 * @return
	 */
	public static <KEY, VALUE> Stream<Tuple2<KEY, VALUE>> scanByKey(Publisher<Tuple2<KEY, VALUE>> publisher,
	                                                                Map<KEY, VALUE> store,
	                                                                Publisher<? extends StreamKv.Signal<KEY, VALUE>>
	                                                                  listener,
	                                                                BiFunction<VALUE, VALUE, VALUE> accumulator) {
		return scanByKeyOn(publisher, store, listener, accumulator);
	}

	/**
	 * @param publisher
	 * @param store
	 * @param listener
	 * @param accumulator
	 * @param <KEY>
	 * @param <VALUE>
	 * @return
	 */
	public static <KEY, VALUE> Stream<Tuple2<KEY, VALUE>> scanByKeyOn(Publisher<Tuple2<KEY, VALUE>> publisher,
	                                                                  Map<KEY, VALUE> store,
	                                                                  Publisher<? extends StreamKv.Signal<KEY,
	                                                                    VALUE>> listener,
	                                                                  BiFunction<VALUE, VALUE, VALUE> accumulator) {
		return new StreamScanByKey<>(publisher, accumulator, listener, store);
	}

	/**
	 * Build a Synchronous {@literal Action} whose data are emitted by the most recent {@link Subscriber#onNext(Object)}
	 * signaled publisher.
	 * The stream will complete once both the publishers source and the last switched to publisher have completed.
	 *
	 * @param <T> type of the value
	 * @return a {@link StreamProcessor} accepting publishers and producing inner data T
	 * @since 2.0
	 */
	public static <T> StreamProcessor<Publisher<? extends T>, T> switchOnNext() {
		Processor<Publisher<? extends T>, Publisher<? extends T>> emitter = Processors.replay();
		StreamProcessor<Publisher<? extends T>, T> p = StreamProcessor.from(emitter, switchOnNext(emitter));
		p.onSubscribe(EmptySubscription.INSTANCE);
		return p;
	}

	/**
	 * Build a Synchronous {@literal Stream} whose data are emitted by the most recent passed publisher.
	 * The stream will complete once both the publishers source and the last switched to publisher have completed.
	 *
	 * @param mergedPublishers The publisher of upstream {@link Publisher} to subscribe to.
	 * @param <T>              type of the value
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T> Stream<T> switchOnNext(
	  Publisher<Publisher<? extends T>> mergedPublishers) {
		return new StreamSwitchMap<>(mergedPublishers, IDENTITY_FUNCTION, XS_QUEUE_SUPPLIER, ReactiveState.XS_BUFFER_SIZE);
	}

	/**
	 * Build a {@literal Stream} that will only emit 0l after the time delay and then complete.
	 *
	 * @param delay the timespan in SECONDS to wait before emitting 0l and complete signals
	 * @return a new {@link Stream}
	 */
	public static Stream<Long> timer(long delay) {
		return timer(Timers.globalOrNew(), delay, TimeUnit.SECONDS);
	}

	/**
	 * Build a {@literal Stream} that will only emit 0l after the time delay and then complete.
	 *
	 * @param timer the timer to run on
	 * @param delay the timespan in SECONDS to wait before emitting 0l and complete signals
	 * @return a new {@link Stream}
	 */
	public static Stream<Long> timer(Timer timer, long delay) {
		return timer(timer, delay, TimeUnit.SECONDS);
	}

	/**
	 * Build a {@literal Stream} that will only emit 0l after the time delay and then complete.
	 *
	 * @param delay the timespan in [unit] to wait before emitting 0l and complete signals
	 * @param unit  the time unit
	 * @return a new {@link Stream}
	 */
	public static Stream<Long> timer(long delay, TimeUnit unit) {
		return timer(Timers.globalOrNew(), delay, unit);
	}

	/**
	 * Uses a resource, generated by a supplier for each individual Subscriber,
	 * while streaming the values from a
	 * Publisher derived from the same resource and makes sure the resource is released
	 * if the sequence terminates or the Subscriber cancels.
	 * <p>
	 * Eager resource cleanup happens just before the source termination and exceptions
	 * raised by the cleanup Consumer may override the terminal even. Non-eager
	 * cleanup will drop any exception.
	 *
	 * @param resourceSupplier a Callable that is called on subscribe
	 * @param sourceSupplier a publisher factory derived from the supplied resource
	 * @param resourceCleanup invoked on completion
	 * @param <T> emitted type
	 * @param <D> resource type
	 * @returna new Stream
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
	 * Eager resource cleanup happens just before the source termination and exceptions
	 * raised by the cleanup Consumer may override the terminal even. Non-eager
	 * cleanup will drop any exception.
	 *
	 * @param resourceSupplier a Callable that is called on subscribe
	 * @param sourceSupplier a publisher factory derived from the supplied resource
	 * @param resourceCleanup invoked on completion
	 * @param eager
	 * @param <T> emitted type
	 * @param <D> resource type
	 * @return new Stream
	 */
	public static <T, D> Stream<T> using(Callable<? extends D> resourceSupplier, Function<? super D, ? extends
			Publisher<? extends T>> sourceSupplier, Consumer<? super D> resourceCleanup, boolean eager) {
		return new StreamUsing<>(resourceSupplier, sourceSupplier, resourceCleanup, eager);
	}

	/**
	 * Build a {@literal Stream} that will only emit 0l after the time delay and then complete.
	 *
	 * @param timer the timer to run on
	 * @param delay the timespan in [unit] to wait before emitting 0l and complete signals
	 * @param unit  the time unit
	 * @return a new {@link Stream}
	 */
	public static Stream<Long> timer(Timer timer, long delay, TimeUnit unit) {
		return new StreamTimerSingle(delay, unit, timer);
	}

	/**
	 * @see Flux#yield(Consumer)
	 * @return a new {@link Stream}
	 */
	public static <T> Stream<T> yield(Consumer<? super ReactiveSession<T>> sessionConsumer) {
		return from(Flux.yield(sessionConsumer));
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the passed publishers.

	 *
	 * @param source1    The first upstream {@link Publisher} to subscribe to.
	 * @param source2    The second upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <T1>       type of the value from source1
	 * @param <T2>       type of the value from source2
	 * @param <V>        The produced output after transformation by {@param combinator}
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	public static <T1, T2, V> Stream<V> zip(Publisher<? extends T1> source1,
	                                        Publisher<? extends T2> source2,
	                                        BiFunction<? super T1, ? super T2, ? extends V> combinator) {
		return from(Flux.zip(source1, source2, combinator));
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the passed publishers.

	 *
	 * @param source1    The first upstream {@link Publisher} to subscribe to.
	 * @param source2    The second upstream {@link Publisher} to subscribe to.
	 *                   value to signal downstream
	 * @param <T1>       type of the value from source1
	 * @param <T2>       type of the value from source2
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2> Stream<Tuple2<T1, T2>> zip(Publisher<? extends T1> source1,
	                                                  Publisher<? extends T2> source2) {
		return from(Flux.zip(source1, source2));
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the passed publishers.

	 *
	 * @param source1    The first upstream {@link Publisher} to subscribe to.
	 * @param source2    The second upstream {@link Publisher} to subscribe to.
	 * @param source3    The third upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <T1>       type of the value from source1
	 * @param <T2>       type of the value from source2
	 * @param <T3>       type of the value from source3
	 * @param <V>        The produced output after transformation by {@param combinator}
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	public static <T1, T2, T3, V> Stream<V> zip(Publisher<? extends T1> source1,
	                                            Publisher<? extends T2> source2,
	                                            Publisher<? extends T3> source3,
	                                            Function<Tuple3<T1, T2, T3>,
	                                              ? extends V> combinator) {
		return from(new FluxZip<>(new Publisher[]{source1, source2, source3},
				combinator,
				ReactiveState.XS_BUFFER_SIZE));
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the passed publishers.

	 *
	 * @param source1    The first upstream {@link Publisher} to subscribe to.
	 * @param source2    The second upstream {@link Publisher} to subscribe to.
	 * @param source3    The third upstream {@link Publisher} to subscribe to.
	 * @param <T1>       type of the value from source1
	 * @param <T2>       type of the value from source2
	 * @param <T3>       type of the value from source3
	 * @return a {@link Stream} based on the produced value
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3> Stream<Tuple3<T1, T2, T3>> zip(Publisher<? extends T1> source1,
												              Publisher<? extends T2> source2,
												              Publisher<? extends T3> source3) {
		return from(new FluxZip<>(new Publisher[]{source1, source2, source3},
				(Function<Tuple3<T1, T2, T3>, Tuple3<T1, T2, T3>>) IDENTITY_FUNCTION,
				ReactiveState.XS_BUFFER_SIZE));
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the passed publishers.

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
	 * @param <V>        The produced output after transformation by {@param combinator}
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	public static <T1, T2, T3, T4, V> Stream<V> zip(Publisher<? extends T1> source1,
	                                                Publisher<? extends T2> source2,
	                                                Publisher<? extends T3> source3,
	                                                Publisher<? extends T4> source4,
	                                                Function<Tuple4<T1, T2, T3, T4>,
	                                                  V> combinator) {
		return from(new FluxZip<>(new Publisher[]{source1, source2, source3, source4},
				combinator,
				ReactiveState.XS_BUFFER_SIZE));
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the passed publishers.

	 *
	 * @param source1    The first upstream {@link Publisher} to subscribe to.
	 * @param source2    The second upstream {@link Publisher} to subscribe to.
	 * @param source3    The third upstream {@link Publisher} to subscribe to.
	 * @param source4    The fourth upstream {@link Publisher} to subscribe to.
	 * @param <T1>       type of the value from source1
	 * @param <T2>       type of the value from source2
	 * @param <T3>       type of the value from source3
	 * @param <T4>       type of the value from source4
	 * @return a {@link Stream} based on the produced value
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4> Stream<Tuple4<T1, T2, T3, T4>> zip(Publisher<? extends T1> source1,
													                  Publisher<? extends T2> source2,
													                  Publisher<? extends T3> source3,
													                  Publisher<? extends T4> source4) {
		return from(new FluxZip<>(new Publisher[]{source1, source2, source3, source4},
				IDENTITY_FUNCTION,
				ReactiveState.XS_BUFFER_SIZE));
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the passed publishers.

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
	 * @param <V>        The produced output after transformation by {@param combinator}
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	public static <T1, T2, T3, T4, T5, V> Stream<V> zip(Publisher<? extends T1> source1,
	                                                    Publisher<? extends T2> source2,
	                                                    Publisher<? extends T3> source3,
	                                                    Publisher<? extends T4> source4,
	                                                    Publisher<? extends T5> source5,
	                                                    Function<Tuple5<T1, T2, T3, T4, T5>,
	                                                      V> combinator) {
		return from(new FluxZip<>(new Publisher[]{source1, source2, source3, source4, source5},
				combinator,
				ReactiveState.XS_BUFFER_SIZE));
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the passed publishers.

	 *
	 * @param source1    The first upstream {@link Publisher} to subscribe to.
	 * @param source2    The second upstream {@link Publisher} to subscribe to.
	 * @param source3    The third upstream {@link Publisher} to subscribe to.
	 * @param source4    The fourth upstream {@link Publisher} to subscribe to.
	 * @param <T1>       type of the value from source1
	 * @param <T2>       type of the value from source2
	 * @param <T3>       type of the value from source3
	 * @param <T4>       type of the value from source4
	 * @param <T5>       type of the value from source5
	 * @return a {@link Stream} based on the produced value
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4, T5> Stream<Tuple5<T1, T2, T3, T4, T5>> zip(Publisher<? extends T1> source1,
														                      Publisher<? extends T2> source2,
														                      Publisher<? extends T3> source3,
														                      Publisher<? extends T4> source4,
														                      Publisher<? extends T5> source5) {
		return from(new FluxZip<>(new Publisher[]{source1, source2, source3, source4, source5},
				(Function<Tuple5<T1, T2, T3, T4, T5>, Tuple5<T1, T2, T3, T4, T5>>) IDENTITY_FUNCTION,
				ReactiveState.XS_BUFFER_SIZE));
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the passed publishers.

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
	 * @param <V>        The produced output after transformation by {@param combinator}
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	public static <T1, T2, T3, T4, T5, T6, V> Stream<V> zip(Publisher<? extends T1> source1,
	                                                        Publisher<? extends T2> source2,
	                                                        Publisher<? extends T3> source3,
	                                                        Publisher<? extends T4> source4,
	                                                        Publisher<? extends T5> source5,
	                                                        Publisher<? extends T6> source6,
	                                                        Function<Tuple6<T1, T2, T3, T4, T5, T6>,
	                                                          V> combinator) {
		return from(new FluxZip<>(new Publisher[]{source1, source2, source3, source4, source5, source6},
				combinator,
				ReactiveState.XS_BUFFER_SIZE));
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the passed publishers.

	 *
	 * @param source1    The first upstream {@link Publisher} to subscribe to.
	 * @param source2    The second upstream {@link Publisher} to subscribe to.
	 * @param source3    The third upstream {@link Publisher} to subscribe to.
	 * @param source4    The fourth upstream {@link Publisher} to subscribe to.
	 * @param source5    The fifth upstream {@link Publisher} to subscribe to.
	 * @param source6    The sixth upstream {@link Publisher} to subscribe to.
	 * @param <T1>       type of the value from source1
	 * @param <T2>       type of the value from source2
	 * @param <T3>       type of the value from source3
	 * @param <T4>       type of the value from source4
	 * @param <T5>       type of the value from source5
	 * @param <T6>       type of the value from source6
	 * @return a {@link Stream} based on the produced value
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4, T5, T6> Stream<Tuple6<T1, T2, T3, T4, T5, T6>> zip(Publisher<? extends T1> source1,
															                          Publisher<? extends T2> source2,
															                          Publisher<? extends T3> source3,
															                          Publisher<? extends T4> source4,
															                          Publisher<? extends T5> source5,
															                          Publisher<? extends T6> source6) {
		return from(new FluxZip<>(new Publisher[]{source1, source2, source3, source4, source5, source6},
				(Function<Tuple6<T1, T2, T3, T4, T5, T6>, Tuple6<T1, T2, T3, T4, T5, T6>>) IDENTITY_FUNCTION,
				ReactiveState.XS_BUFFER_SIZE));
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the passed publishers.

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
	 * @param <V>        The produced output after transformation by {@param combinator}
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	public static <T1, T2, T3, T4, T5, T6, T7, V> Stream<V> zip(Publisher<? extends T1> source1,
	                                                            Publisher<? extends T2> source2,
	                                                            Publisher<? extends T3> source3,
	                                                            Publisher<? extends T4> source4,
	                                                            Publisher<? extends T5> source5,
	                                                            Publisher<? extends T6> source6,
	                                                            Publisher<? extends T7> source7,
	                                                            Function<Tuple7<T1, T2, T3, T4, T5, T6, T7>,
	                                                              V> combinator) {
		return from(new FluxZip<>(new Publisher[]{source1, source2, source3, source4, source5, source6, source7},
				combinator,
				ReactiveState.XS_BUFFER_SIZE));
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the passed publishers.

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
	 * @return a {@link Stream} based on the produced value
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4, T5, T6, T7> Stream<Tuple7<T1, T2, T3, T4, T5, T6, T7>> zip(
			Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			Publisher<? extends T3> source3,
			Publisher<? extends T4> source4,
			Publisher<? extends T5> source5,
			Publisher<? extends T6> source6,
			Publisher<? extends T7> source7) {
		return from(new FluxZip<>(new Publisher[]{source1, source2, source3, source4, source5, source6, source7},
				IDENTITY_FUNCTION,
				ReactiveState.XS_BUFFER_SIZE));
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the passed publishers.

	 *
	 * @param sources    The list of upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <V>        The produced output after transformation by {@param combinator}
	 * @param <TUPLE>    The type of tuple to use that must match source Publishers type
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	public static <TUPLE extends Tuple, V> Stream<V> zip(Iterable<? extends Publisher<?>> sources,
			final Function<? super TUPLE, ? extends V> combinator) {
		return from(Flux.zip(sources, new Function<Tuple, V>() {
			@Override
			@SuppressWarnings("unchecked")
			public V apply(Tuple tuple) {
				return combinator.apply((TUPLE)tuple);
			}
		}));
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the passed publishers.

	 *
	 * @param sources    The list of upstream {@link Publisher} to subscribe to.
	 * @param <TUPLE>    The type of tuple to use that must match source Publishers type
	 * @return a {@link Stream} based on the produced value
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <TUPLE extends Tuple> Stream<TUPLE> zip(Iterable<? extends Publisher<?>> sources) {
		return from((Publisher<TUPLE>) Flux.zip(sources));
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the passed publishers.

	 *
	 * @param sources    The publisher of upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 *                   value to signal downstream
	 * @param <V>        The produced output after transformation by {@param combinator}
	 * @return a {@link Stream} based on the produced value
	 * @since 2.0
	 */
	public static <TUPLE extends Tuple, V> Stream<V> zip(
	  Publisher<? extends Publisher<?>> sources,
	  final Function<? super TUPLE, ? extends V> combinator) {

		return from(sources).buffer()
		                    .flatMap(new Function<List<? extends Publisher<?>>, Publisher<V>>() {
			@Override
			public Publisher<V> apply(List<? extends Publisher<?>> publishers) {
				return new FluxZip<>(publishers.toArray(
						new Publisher[publishers.size()]),
						combinator,
						ReactiveState.XS_BUFFER_SIZE);
			}
		});
	}

	/**
	 * Build a {@literal Stream} whose data are generated by the passed publishers.

	 *
	 * @param sources    The publisher of upstream {@link Publisher} to subscribe to.
	 * @return a {@link Stream} based on the produced value
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public static Stream<Tuple> zip(Publisher<? extends Publisher<?>> sources) {
		return zip(sources, (Function<Tuple, Tuple>) IDENTITY_FUNCTION);
	}

	protected Stream() {
	}

	/**
	 *
	 * {@code stream.as(Mono::from).subscribe(Subscribers.unbounded()) }
	 *
	 * @param transformer
	 * @param <P>
	 *
	 * @return
	 */
	public final <V, P extends Publisher<V>> P as(Function<? super Stream<O>, P> transformer) {
		return transformer.apply(this);
	}

	/**
	 * Ignore the sequence and return onComplete
	 *
	 * @return {@literal new Stream}
	 *
	 * @see Flux#after)
	 */
	public final Mono<Void> after() {
		return new MonoIgnoreElements<>(this);
	}

	/**
	 * Return a {@code Stream<V>} that completes when this {@link Mono} completes.
	 *
	 * @param sourceSupplier
	 * @param <V>
	 *
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public final <V> Stream<V> after(Supplier<? extends Publisher<V>> sourceSupplier) {
		return new StreamBarrier<>(new FluxFlatMap<>(
				new FluxMapSignal<>(after(), null, null, sourceSupplier),
				IDENTITY_FUNCTION,
				ReactiveState.SMALL_BUFFER_SIZE, 32));
	}

	/**
	 * @param predicate
	 *
	 * @return
	 */
	public final Mono<Boolean> all(Predicate<? super O> predicate) {
		return new MonoAll<>(this, predicate);
	}

	/**
	 * Select the first emitting Publisher between this and the given publisher. The "loosing" one will be cancelled
	 * while the winning one will emit normally to the returned Stream.
	 *
	 * @return the ambiguous stream
	 *
	 * @since 2.5
	 */
	public final Stream<O> ambWith(final Publisher<? extends O> publisher) {
		return new StreamBarrier<O, O>(this) {
			@Override
			public String getName() {
				return "ambWith";
			}

			@Override
			public void subscribe(Subscriber<? super O> s) {
				Flux.amb(Stream.this, publisher)
				    .subscribe(s);
			}
		};
	}

	/**
	 * Subscribe a new {@link Broadcaster} and return it for future subscribers interactions. Effectively it turns any
	 * stream into an Hot Stream where subscribers will only values from the time T when they subscribe to the returned
	 * stream. Complete and Error signals are however retained. <p>
	 *
	 * @return a new {@literal stream} whose values are broadcasted to all subscribers
	 */
	public final Stream<O> broadcast() {
		Broadcaster<O> broadcaster = Broadcaster.create(getTimer());
		return broadcastTo(broadcaster);
	}

	/**
	 * Subscribe the passed subscriber, only creating once necessary upstream Subscriptions and returning itself. Mostly
	 * used by other broadcast actions which transform any Stream into a publish-subscribe Stream (every subscribers see
	 * all values). <p>
	 *
	 * @param subscriber the subscriber to subscribe to this stream and return
	 * @param <E> the hydrated generic type for the passed argument, allowing for method chaining
	 *
	 * @return {@param subscriber}
	 */
	public final <E extends Subscriber<? super O>> E broadcastTo(E subscriber) {
		subscribe(subscriber);
		return subscriber;
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@code Stream} every
	 * time {@link #getCapacity()} has been reached, or flush is triggered.
	 *
	 * @return a new {@link Stream} whose values are a {@link List} of all values in this batch
	 */
	public final Stream<List<O>> buffer() {
		return buffer(Integer.MAX_VALUE);
	}

	/**
	 * Collect incoming values into multiple {@link List} buckets that will be pushed into the returned {@code Stream}
	 * every time {@link #getCapacity()} has been reached.
	 *
	 * @param maxSize the collected size
	 *
	 * @return a new {@link Stream} whose values are a {@link List} of all values in this batch
	 */
	@SuppressWarnings("unchecked")
	public final Stream<List<O>> buffer(final int maxSize) {
		return new StreamBuffer<>(this, maxSize, (Supplier<List<O>>) LIST_SUPPLIER);
	}

	/**
	 * Collect incoming values into a {@link List} that will be moved into the returned {@code Stream} every time the
	 * passed boundary publisher emits an item. Complete will flush any remaining items.
	 *
	 * @param bucketOpening the publisher to subscribe to on start for creating new buffer on next or complete signals.
	 * @param boundarySupplier the factory to provide a publisher to subscribe to when a buffer has been started
	 *
	 * @return a new {@link Stream} whose values are a {@link List} of all values in this batch
	 *
	 * @since 2.5
	 */
	@SuppressWarnings("unchecked")
	public final <U, V> Stream<List<O>> buffer(final Publisher<U> bucketOpening,
			final Function<? super U, ? extends Publisher<V>> boundarySupplier) {

		return new StreamBufferStartEnd<>(this, bucketOpening, boundarySupplier, LIST_SUPPLIER, XS_QUEUE_SUPPLIER);
	}

	/**
	 * Collect incoming values into a {@link List} that will be moved into the returned {@code Stream} every time the
	 * passed boundary publisher emits an item. Complete will flush any remaining items.
	 *
	 * @param boundarySupplier the factory to provide a publisher to subscribe to on start for emiting and starting a
	 * new buffer
	 *
	 * @return a new {@link Stream} whose values are a {@link List} of all values in this batch
	 */
	@SuppressWarnings("unchecked")
	public final Stream<List<O>> buffer(final Publisher<?> boundarySupplier) {
		return new StreamBufferBoundary<>(this, boundarySupplier, LIST_SUPPLIER);
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@code Stream} every time
	 * {@code maxSize} has been reached by any of them. Complete signal will flush any remaining buckets.
	 *
	 * @param skip the number of items to skip before creating a new bucket
	 * @param maxSize the collected size
	 *
	 * @return a new {@link Stream} whose values are a {@link List} of all values in this batch
	 */
	@SuppressWarnings("unchecked")
	public final Stream<List<O>> buffer(final int maxSize, final int skip) {
		if (maxSize == skip) {
			return buffer(maxSize);
		}
		return new StreamBuffer<>(this, maxSize, skip, LIST_SUPPLIER);
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@code Stream} every timespan.
	 *
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit the time unit
	 *
	 * @return a new {@link Stream} whose values are a {@link List} of all values in this batch
	 */
	public final Stream<List<O>> buffer(long timespan, TimeUnit unit) {
		return buffer(timespan, unit, getTimer());
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@code Stream} every timespan.
	 *
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit the time unit
	 * @param timer the Timer to run on
	 *
	 * @return a new {@link Stream} whose values are a {@link List} of all values in this batch
	 */
	public final Stream<List<O>> buffer(long timespan, TimeUnit unit, Timer timer) {
		return buffer(Integer.MAX_VALUE, timespan, unit, timer);
	}

	/**
	 * Collect incoming values into multiple {@link List} buckets created every {@code timeshift }that will be pushed
	 * into the returned {@code Stream} every timespan. Complete signal will flush any remaining buckets.
	 *
	 * @param timespan the period in unit to use to release buffered lists
	 * @param timeshift the period in unit to use to create a new bucket
	 * @param unit the time unit
	 *
	 * @return a new {@link Stream} whose values are a {@link List} of all values in this batch
	 */
	public final Stream<List<O>> buffer(final long timespan, final long timeshift, final TimeUnit unit) {
		return buffer(timespan, timeshift, unit, getTimer());
	}

	/**
	 * Collect incoming values into multiple {@link List} buckets created every {@code timeshift }that will be pushed
	 * into the returned {@code Stream} every timespan. Complete signal will flush any remaining buckets.
	 *
	 * @param timespan the period in unit to use to release buffered lists
	 * @param timeshift the period in unit to use to create a new bucket
	 * @param unit the time unit
	 * @param timer the Timer to run on
	 *
	 * @return a new {@link Stream} whose values are a {@link List} of all values in this batch
	 */
	public final Stream<List<O>> buffer(final long timespan,
			final long timeshift,
			final TimeUnit unit,
			final Timer timer) {
		if (timespan == timeshift) {
			return buffer(timespan, unit, timer);
		}
		return new StreamBufferShiftTimeout<O>(this, timeshift, timespan, unit, timer);
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@code Stream} every timespan
	 * OR maxSize items.
	 *
	 * @param maxSize the max collected size
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit the time unit
	 *
	 * @return a new {@link Stream} whose values are a {@link List} of all values in this batch
	 */
	public final Stream<List<O>> buffer(int maxSize, long timespan, TimeUnit unit) {
		return buffer(maxSize, timespan, unit, getTimer());
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@code Stream} every timespan
	 * OR maxSize items
	 *
	 * @param maxSize the max collected size
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit the time unit
	 * @param timer the Timer to run on
	 *
	 * @return a new {@link Stream} whose values are a {@link List} of all values in this batch
	 */
	public final Stream<List<O>> buffer(final int maxSize,
			final long timespan,
			final TimeUnit unit,
			final Timer timer) {
		return new StreamBufferTimeout<>(this, maxSize, timespan, unit, timer);
	}

	/**
	 * Cache last {@link ReactiveState#SMALL_BUFFER_SIZE} signal to this {@code Stream} and release them on request that
	 * will observe any values accepted by this {@code Stream}.
	 *
	 * @return {@literal new Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> cache() {
		return cache(ReactiveState.SMALL_BUFFER_SIZE);
	}

	/**
	 * Cache all signal to this {@code Stream} and release them on request that will observe any values accepted by this
	 * {@code Stream}.
	 *
	 * @param last number of events retained in history
	 *
	 * @return {@literal new Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> cache(int last) {
		Processor<O, O> emitter = Processors.replay(last);
		subscribe(emitter);
		return StreamProcessor.from(emitter);
	}

	/**
	 * Bind the stream to a given {@param elements} volume of in-flight data: - A {@link Subscriber} will request up to
	 * the defined volume upstream. - a {@link Subscriber} will track the pending requests and fire up to {@param
	 * elements} when the previous volume has been processed. - A {@link StreamBatch} and any other size-bound action
	 * will be limited to the defined volume. <p> <p> A stream capacity can't be superior to the underlying dispatcher
	 * capacity: if the {@param elements} overflow the dispatcher backlog size, the capacity will be aligned
	 * automatically to fit it. RingBufferDispatcher will for instance take to a power of 2 size up to {@literal
	 * Integer.MAX_VALUE}, where a Stream can be sized up to {@literal Long.MAX_VALUE} in flight data. <p> <p> When the
	 * stream receives more elements than requested, incoming data is eventually staged in a {@link
	 * Subscription}. The subscription can react differently according to the implementation in-use,
	 * the default strategy is as following: - The first-level of pair compositions Stream->Subscriber will overflow
	 * data in a {@link Queue}, ready to be polled when the action fire the pending requests. - The following
	 * pairs of Subscriber->Subscriber will synchronously pass data - Any pair of Stream->Subscriber or
	 * Subscriber->Subscriber will behave as with the root Stream->Action pair rule. - {@link #onBackpressureBuffer()} force
	 * this staging behavior, with a possibilty to pass a {@link reactor.core.queue .PersistentQueue}
	 *
	 * @param elements maximum number of in-flight data
	 *
	 * @return a backpressure capable stream
	 */
	public Stream<O> capacity(final long elements) {
		if (elements == getCapacity()) {
			return this;
		}

		return new StreamBarrier<O, O>(this) {
			@Override
			public long getCapacity() {
				return elements;
			}

			@Override
			public String getName() {
				return "capacitySetup";
			}
		};
	}

	/**
	 * Cast the current Stream flowing data type into a target class type.
	 *
	 * @param <E> the {@link Stream} output type
	 *
	 * @return the current {link Stream} instance casted
	 *
	 * @since 2.0
	 */
	@SuppressWarnings({"unchecked", "unused"})
	public final <E> Stream<E> cast(final Class<E> stream) {
		return (Stream<E>) this;
	}

	/**
	 * Collect the stream sequence with the given collector with the supplied container
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
	 * Assign the given {@link Function} to transform the incoming value {@code T} into a {@code Stream<O,V>} and pass
	 * it into another {@code Stream}. The produced stream will emit the data from all transformed streams in order.
	 *
	 * @param fn the transformation function
	 * @param <V> the type of the return value of the transformation function
	 *
	 * @return a new {@link Stream} containing the transformed values
	 *
	 * @since 1.1, 2.0
	 */
	public final <V> Stream<V> concatMap(final Function<? super O, Publisher<? extends V>> fn) {
		return new StreamBarrier<O, V>(this) {
			@Override
			public String getName() {
				return "concatMap";
			}

			@Override
			public void subscribe(Subscriber<? super V> s) {
				new FluxFlatMap<>(Stream.this, fn, ReactiveState.SMALL_BUFFER_SIZE, 1)
				    .subscribe(s);
			}
		};
	}

	/**
	 * Pass all the nested {@link Publisher} values from this current upstream and then on complete consume from the
	 * passed publisher.
	 *
	 * @return the merged stream
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final Stream<O> concatWith(final Publisher<? extends O> publisher) {
		return new StreamConcatArray<>(this, publisher);
	}

	/**
	 * Instruct the stream to request the produced subscription indefinitely. If the dispatcher is asynchronous
	 * (RingBufferDispatcher for instance), it will proceed the request asynchronously as well.
	 *
	 * @return the consuming action
	 */
	@SuppressWarnings("unchecked")
	public Control consume() {
		return consume(NOOP);
	}

	/**
	 * Attach a {@link Consumer} to this {@code Stream} that will consume any values accepted by this {@code Stream}. As
	 * such this a terminal action to be placed on a stream flow. It will also eagerly prefetch upstream publisher. <p>
	 * For a passive version that observe and forward incoming data see {@link #doOnNext(reactor.fn.Consumer)}
	 *
	 * @param consumer the consumer to invoke on each value
	 *
	 * @return a new {@link Control} interface to operate on the materialized upstream
	 */
	public final Control consume(final Consumer<? super O> consumer) {
		long c = Math.min(Integer.MAX_VALUE, getCapacity());
		InterruptableSubscriber<O> consumerAction;
		if (c == Integer.MAX_VALUE) {
			consumerAction = new InterruptableSubscriber<O>(consumer, null, null);
		}
		else {
			consumerAction = new BoundedSubscriber<O>((int) c, consumer, null, null);
		}
		subscribe(consumerAction);
		return consumerAction;
	}

	/**
	 * Attach 2 {@link Consumer} to this {@code Stream} that will consume any values signaled by this {@code Stream}. As
	 * such this a terminal action to be placed on a stream flow. Any Error signal will be consumed by the error
	 * consumer. It will also eagerly prefetch upstream publisher. <p>
	 *
	 * @param consumer the consumer to invoke on each next signal
	 * @param errorConsumer the consumer to invoke on each error signal
	 *
	 * @return a new {@link Control} interface to operate on the materialized upstream
	 */
	public final Control consume(final Consumer<? super O> consumer, Consumer<? super Throwable> errorConsumer) {
		return consume(consumer, errorConsumer, null);
	}

	/**
	 * Attach 3 {@link Consumer} to this {@code Stream} that will consume any values signaled by this {@code Stream}. As
	 * such this a terminal action to be placed on a stream flow. Any Error signal will be consumed by the error
	 * consumer. The Complete signal will be consumed by the complete consumer. Only error and complete signal will be
	 * signaled downstream. It will also eagerly prefetch upstream publisher. <p>
	 *
	 * @param consumer the consumer to invoke on each value
	 * @param errorConsumer the consumer to invoke on each error signal
	 * @param completeConsumer the consumer to invoke on complete signal
	 *
	 * @return {@literal new Stream}
	 */
	public final Control consume(final Consumer<? super O> consumer,
			Consumer<? super Throwable> errorConsumer,
			Runnable completeConsumer) {

		long c = Math.min(Integer.MAX_VALUE, getCapacity());

		InterruptableSubscriber<O> consumerAction;
		if (c == Integer.MAX_VALUE) {
			consumerAction = new InterruptableSubscriber<O>(consumer, errorConsumer, completeConsumer);
		}
		else {
			consumerAction = new BoundedSubscriber<O>((int) c, consumer, errorConsumer, completeConsumer);
		}

		subscribe(consumerAction);
		return consumerAction;
	}

	/**
	 * Defer a Controls operations ready to be requested.
	 *
	 * @return the consuming action
	 */
	public Control.Demand consumeLater() {
		return consumeLater(null);
	}

	/**
	 * Attach a {@link Consumer} to this {@code Stream} that will consume any values accepted by this {@code Stream}. As
	 * such this a terminal action to be placed on a stream flow. It will also eagerly prefetch upstream publisher. <p>
	 * For a passive version that observe and forward incoming data see {@link #doOnNext(reactor.fn.Consumer)}
	 *
	 * @param consumer the consumer to invoke on each value
	 *
	 * @return a new {@link Control} interface to operate on the materialized upstream
	 */
	public final Control.Demand consumeLater(final Consumer<? super O> consumer) {
		return consumeLater(consumer, null, null);
	}

	/**
	 * Attach 2 {@link Consumer} to this {@code Stream} that will consume any values signaled by this {@code Stream}. As
	 * such this a terminal action to be placed on a stream flow. Any Error signal will be consumed by the error
	 * consumer. It will also eagerly prefetch upstream publisher. <p>
	 *
	 * @param consumer the consumer to invoke on each next signal
	 * @param errorConsumer the consumer to invoke on each error signal
	 *
	 * @return a new {@link Control} interface to operate on the materialized upstream
	 */
	public final Control.Demand consumeLater(final Consumer<? super O> consumer,
			Consumer<? super Throwable> errorConsumer) {
		return consumeLater(consumer, errorConsumer, null);
	}

	/**
	 * Attach 3 {@link Consumer} to this {@code Stream} that will consume any values signaled by this {@code Stream}. As
	 * such this a terminal action to be placed on a stream flow. Any Error signal will be consumed by the error
	 * consumer. The Complete signal will be consumed by the complete consumer. Only error and complete signal will be
	 * signaled downstream. It will also eagerly prefetch upstream publisher. <p>
	 *
	 * @param consumer the consumer to invoke on each value
	 * @param errorConsumer the consumer to invoke on each error signal
	 * @param completeConsumer the consumer to invoke on complete signal
	 *
	 * @return {@literal new Stream}
	 */
	public final Control.Demand consumeLater(final Consumer<? super O> consumer,
			Consumer<? super Throwable> errorConsumer,
			Runnable completeConsumer) {
		ManualSubscriber<O> consumerAction = new ManualSubscriber<>(consumer, errorConsumer, completeConsumer);
		subscribe(consumerAction);
		return consumerAction;
	}

	/**
	 * Attach a {@link Consumer} to this {@code Stream} that will consume any values accepted by this {@code Stream}. As
	 * such this a terminal action to be placed on a stream flow. It will also eagerly prefetch upstream publisher. <p>
	 * The passed {code requestMapper} function will receive the {@link Stream} of the last N requested elements
	 * -starting with the capacity defined for the stream- when the N elements have been consumed. It will return a
	 * {@link Publisher} of long signals S that will instruct the consumer to request S more elements. <p> For a passive
	 * version that observe and forward incoming data see {@link #doOnNext(reactor.fn.Consumer)}
	 *
	 * @param consumer the consumer to invoke on each value
	 *
	 * @return a new {@link Control} interface to operate on the materialized upstream
	 */
	public final Control consumeWhen(final Consumer<? super O> consumer,
			final Function<Stream<Long>, ? extends Publisher<? extends Long>> requestMapper) {
		AdaptiveSubscriber<O> consumerAction = new AdaptiveSubscriber<O>(getTimer(), consumer, requestMapper);

		subscribe(consumerAction);
		return consumerAction;
	}

	/**
	 * Attach a {@link Consumer} to this {@code Stream} that will consume any values accepted by this {@code Stream}. As
	 * such this a terminal action to be placed on a stream flow. It will also eagerly prefetch upstream publisher. <p>
	 * The passed {code requestMapper} function will receive the {@link Stream} of the last N requested elements
	 * -starting with the capacity defined for the stream- when the N elements have been consumed. It will return a
	 * {@link Publisher} of long signals S that will instruct the consumer to request S more elements, possibly altering
	 * the "batch" size if wished. <p> <p> For a passive version that observe and forward incoming data see {@link
	 * #doOnNext(reactor.fn.Consumer)}
	 *
	 * @param consumer the consumer to invoke on each value
	 *
	 * @return a new {@link Control} interface to operate on the materialized upstream
	 */
	public final Control consumeWithRequest(final Consumer<? super O> consumer,
			final Function<Long, ? extends Long> requestMapper) {
		return consumeWhen(consumer, new Function<Stream<Long>, Publisher<? extends Long>>() {
			@Override
			public Publisher<? extends Long> apply(Stream<Long> longStream) {
				return longStream.map(requestMapper);
			}
		});
	}

	/**
	 * Count accepted events for each batch and pass each accumulated long to the {@param stream}.
	 */
	public final Mono<Long> count() {
		return new MonoCount<>(this);
	}

	/**
	 * Print a debugged form of the root action relative to this one. The output will be an acyclic directed graph of
	 * composed actions.
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public ReactiveStateUtils.Graph debug() {
		return ReactiveStateUtils.scan(this);
	}

	/**
	 * Create an operation that returns the passed value if the Stream has completed without any emitted signals.
	 *
	 * @param defaultValue the value to forward if the stream is empty
	 *
	 * @return {@literal new Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> defaultIfEmpty(final O defaultValue) {
		return new StreamBarrier<>(new FluxDefaultIfEmpty<O>(this, defaultValue));
	}

	/**
	 * @param subscriptionDelay
	 * @param <U>
	 *
	 * @return
	 */
	public final <U> Stream<O> delaySubscription(Publisher<U> subscriptionDelay) {
		return new StreamDelaySubscription<>(this, subscriptionDelay);
	}

	/**
	 * Transform the incoming onSubscribe, onNext, onError and onComplete signals into {@link reactor.rx.stream
	 * .Signal}. Since the error is materialized as a {@code Signal}, the propagation will be stopped. Complete signal
	 * will first emit a {@code Signal.complete()} and then effectively complete the stream.
	 *
	 * @return {@literal new Stream}
	 */
	@SuppressWarnings("unchecked")
	public final <X> Stream<X> dematerialize() {
		Stream<Signal<X>> thiz = (Stream<Signal<X>>) this;
		return new StreamDematerialize<>(thiz);
	}

	/**
	 *
	 */
	public final Stream<O> dispatchOn(final ProcessorGroup processorProvider) {
		return new DispatchOn<>(this, processorProvider);
	}

	/**
	 * Create a new {@code Stream} that filters in only unique values.
	 *
	 * @return a new {@link Stream} with unique values
	 */
	@SuppressWarnings("unchecked")
	public final Stream<O> distinct() {
		return new StreamDistinct<>(this, HASHCODE_EXTRACTOR, hashSetSupplier());
	}

	/**
	 * Create a new {@code Stream} that filters in only values having distinct keys computed by function
	 *
	 * @param keySelector function to compute comparison key for each element
	 *
	 * @return a new {@link Stream} with values having distinct keys
	 */
	public final <V> Stream<O> distinct(final Function<? super O, ? extends V> keySelector) {
		return new StreamDistinct<>(this, keySelector, hashSetSupplier());
	}

	/**
	 * Create a new {@code Stream} that filters out consecutive equals values.
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
	 * Create a new {@code Stream} that filters out consecutive values having equal keys computed by function
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
	 * Create a new {@code Stream} that accepts a {@link reactor.fn.tuple.Tuple2} of T1 {@link Long} timemillis and T2
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
	 * Create a new {@code Stream} that emits an item at a specified index from a source {@code Stream}
	 *
	 * @param index index of an item
	 *
	 * @return a source item at a specified index
	 */
	public final Mono<O> elementAt(final int index) {
		return new MonoElementAt<O>(this, index);
	}

	/**
	 * Create a new {@code Stream} that emits an item at a specified index from a source {@code Stream} or default value
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
	 * Create a new {@code Stream} that emits <code>true</code> when any value satisfies a predicate and
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
	 * passed into the new {@code Stream}. If the predicate test fails, the value is ignored.
	 *
	 * @param p the {@link Predicate} to test values against
	 *
	 * @return a new {@link Stream} containing only values that pass the predicate test
	 */
	public final Stream<O> filter(final Predicate<? super O> p) {
		return new StreamFilter<>(this, p);
	}

	/**
	 * Attach a {@link Consumer} to this {@code Stream} that will observe terminal signal complete|error. The consumer
	 * will listen for the signal and introspect its state.
	 *
	 * @param consumer the consumer to invoke on terminal signal
	 *
	 * @return {@literal new Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> finallyDo(final Consumer<Signal<O>> consumer) {
		return new StreamFinally<O>(this, consumer);
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into a {@code Stream<O,V>} and pass
	 * it into another {@code Stream}.
	 *
	 * @param fn the transformation function
	 * @param <V> the type of the return value of the transformation function
	 *
	 * @return a new {@link Stream} containing the transformed values
	 *
	 * @since 1.1, 2.0
	 */
	public final <V> Stream<V> flatMap(final Function<? super O, ? extends Publisher<? extends V>> fn) {

		return new StreamBarrier<O, V>(this) {
			@Override
			public String getName() {
				return "flatMap";
			}

			@Override
			public void subscribe(Subscriber<? super V> s) {
				new FluxFlatMap<>(Stream.this, fn, ReactiveState.SMALL_BUFFER_SIZE, ReactiveState.XS_BUFFER_SIZE)
				          .subscribe(s);
			}
		};
	}

	/**
	 * Transform the signals emitted by this {@link Flux} into Publishers, then flatten the emissions from those by
	 * merging them into a single {@link Flux}, so that they may interleave.
	 *
	 * @param mapperOnNext
	 * @param mapperOnError
	 * @param mapperOnComplete
	 * @param <R>
	 *
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public final <R> Stream<R> flatMap(Function<? super O, ? extends Publisher<? extends R>> mapperOnNext,
			Function<Throwable, ? extends Publisher<? extends R>> mapperOnError,
			Supplier<? extends Publisher<? extends R>> mapperOnComplete) {
		return new StreamBarrier<>(new FluxFlatMap<>(
				new FluxMapSignal<>(this, mapperOnNext, mapperOnError, mapperOnComplete),
				IDENTITY_FUNCTION,
				ReactiveState.SMALL_BUFFER_SIZE, 32)
		);
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into a {@code Stream<O,V>} and pass
	 * it into another {@code Stream}.
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
			pub = fn.apply(new GroupedStream<Integer, O>(i) {
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

		final Publisher<V> mergedStream = Flux.merge(publisherList);

		return new StreamBarrier<O, V>(this) {
			@Override
			public String getName() {
				return "forkJoin";
			}

			@Override
			public void subscribe(Subscriber<? super V> s) {
				mergedStream.subscribe(s);
			}
		};
	}

	@Override
	public long getCapacity() {
		return Long.MAX_VALUE;
	}

	/**
	 * Get the current timer available if any or try returning the shared Environment one (which may cause an error if
	 * no Environment has been globally initialized)
	 *
	 * @return any available timer
	 */
	public Timer getTimer() {
		return Timers.globalOrNull();
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
	public final <K> Stream<GroupedStream<K, O>> groupBy(final Function<? super O, ? extends K> keyMapper) {
		return new StreamGroupBy<>(this, keyMapper, getTimer());
	}

	/**
	 * @return {@literal new Stream}
	 *
	 * @see Flux#after)
	 */
	@SuppressWarnings("unchecked")
	public final Mono<O> ignoreElements() {
		return (Mono<O>) new MonoIgnoreElements<>(this);
	}

	/**
	 * @return
	 */
	public final Mono<Boolean> isEmpty() {
		return new MonoIsEmpty<>(this);
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
		return zipWith(publisher, (BiFunction<Object, Object, List<T>>) FluxZip.JOIN_BIFUNCTION);
	}

	/**
	 * Create a new {@code Stream} that will signal the last element observed before complete signal.
	 *
	 * @return a new limited {@code Stream}
	 *
	 * @since 2.0
	 */
	public final Mono<O> last() {
		return new Mono.MonoBarrier<>(new StreamTakeLast<>(this, 1));
	}


	/**
	 * @see {@link Flux#lift(Function)}
	 * @since 2.5
	 */
	public <V> Stream<V> lift(final Function<Subscriber<? super V>, Subscriber<? super O>> operator) {
		return lift(this, operator);
	}

	/**
	 * Defer the subscription of a {@link Processor} to the actual pipeline. Terminal operations such as {@link
	 * #consume(reactor.fn.Consumer)} will start the subscription chain. It will listen for current Stream signals and
	 * will be eventually producing signals as well (subscribe,error, complete,next). <p> The action is returned for
	 * functional-style chaining.
	 *
	 * @param <V> the {@link Stream} output type
	 * @param processorSupplier the function to map a provided dispatcher to a fresh Action to subscribe.
	 *
	 * @return the passed action
	 *
	 * @see {@link Publisher#subscribe(Subscriber)}
	 * @since 2.0
	 */
	public <V> Stream<V> liftProcessor(final Supplier<? extends Processor<O, V>> processorSupplier) {
		return lift(new Flux.Operator<O, V>() {
			@Override
			public Subscriber<? super O> apply(Subscriber<? super V> subscriber) {
				Processor<O, V> processor = processorSupplier.get();
				processor.subscribe(subscriber);
				return processor;
			}
		});
	}

	/**
	 * Attach a {@link reactor.core.support.Logger} to this {@code Stream} that will observe any signal emitted.
	 *
	 * @return {@literal new Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> log() {
		return log(null, FluxLog.ALL);
	}

	/**
	 * Attach a {@link reactor.core.support.Logger} to this {@code Stream} that will observe any signal emitted.
	 *
	 * @param category The logger name
	 *
	 * @return {@literal new Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> log(String category) {
		return log(category, FluxLog.ALL);
	}

	/**
	 * Attach a {@link reactor.core.support.Logger} to this {@code Stream} that will observe any signal emitted.
	 *
	 * @param category The logger name
	 * @param options the bitwise checked flags for observed signals
	 *
	 * @return {@literal new Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> log(final String category, int options) {
		return log(category, Level.INFO, options);
	}

	/**
	 * Attach a {@link reactor.core.support.Logger} to this {@code Stream} that will observe any signal emitted.
	 *
	 * @param category The logger name
	 * @param level The logger level
	 * @param options the bitwise checked flags for observed signals
	 *
	 * @return {@literal new Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> log(final String category, Level level, int options) {
		return new StreamBarrier.Identity<>(new FluxLog<O>(this, category, level, options));
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into a {@code V} and pass it into
	 * another {@code Stream}.
	 *
	 * @param fn the transformation function
	 * @param <V> the type of the return value of the transformation function
	 *
	 * @return a new {@link Stream} containing the transformed values
	 */
	public final <V> Stream<V> map(final Function<? super O, ? extends V> fn) {
		return new StreamMap<>(this, fn);
	}

	/**
	 * Transform the incoming onSubscribe, onNext, onError and onComplete signals into {@link reactor.rx.stream
	 * .Signal}. Since the error is materialized as a {@code Signal}, the propagation will be stopped. Complete signal
	 * will first emit a {@code Signal.complete()} and then effectively complete the stream.
	 *
	 * @return {@literal new Stream}
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

		return new StreamBarrier<O, V>(this) {
			@Override
			public String getName() {
				return "merge";
			}

			@Override
			public void subscribe(Subscriber<? super V> s) {
				Flux.merge(thiz)
				    .subscribe(s);
			}
		};
	}

	/**
	 * Pass all the nested {@link Publisher} values from this current upstream and from the passed publisher.
	 *
	 * @return the merged stream
	 *
	 * @since 2.0
	 */
	public final Stream<O> mergeWith(final Publisher<? extends O> publisher) {
		return new StreamBarrier<O, O>(this) {
			@Override
			public String getName() {
				return "mergeWith";
			}

			@Override
			public void subscribe(Subscriber<? super O> s) {
				Flux.merge(Stream.this, publisher)
				    .subscribe(s);
			}
		};
	}

	/**
	 * Create a new {@code Stream} whose only value will be the current instance of the {@link Stream}.
	 *
	 * @return a new {@link Stream} whose only value will be the materialized current {@link Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<Stream<O>> nest() {
		return just(this);
	}

	/**
	 * Return the promise of the next triggered signal. A promise is a container that will capture only once the first
	 * arriving error|next|complete signal to this {@link Stream}. It is useful to coordinate on single data streams or
	 * await for any signal.
	 *
	 * @return a new {@link Mono}
	 *
	 * @since 2.0, 2.5
	 */
	public final Mono<O> next() {
		return new MonoNext<>(this);
	}

	/**
	 * Attach a {@link Consumer} to this {@code Stream} that will observe any values accepted by this {@code Stream}.
	 *
	 * @param consumer the consumer to invoke on each value
	 *
	 * @return {@literal new Stream}
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> doOnNext(final Consumer<? super O> consumer) {
		return new StreamCallback<O>(this, consumer, null);
	}

	/**
	 * Attach a {@link Runnable} to this {@code Stream} that will observe any cancel signal
	 *
	 * @param runnable the runnable to invoke on cancel
	 *
	 * @return {@literal a new stream}
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> doOnCancel(final Runnable runnable) {
		return new StreamStateCallback<O>(this, runnable, null);
	}

	/**
	 * Attach a {@link Runnable} to this {@code Stream} that will observe any complete signal
	 *
	 * @param consumer the consumer to invoke on complete
	 *
	 * @return {@literal a new stream}
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> doOnComplete(final Runnable consumer) {
		return new StreamCallback<O>(this, null, consumer);
	}

	/**
	 * Attach a {@link Consumer} to this {@code Stream} that will observe any error signal
	 *
	 * @param consumer the runnable to invoke on cancel
	 *
	 * @return {@literal a new stream}
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> doOnError(final Consumer<Throwable> consumer) {
		return new StreamError<>(this, Throwable.class, consumer);
	}

	/**
	 * Attach a {@link Consumer} to this {@code Stream} that will observe any onSubscribe signal
	 *
	 * @param consumer the consumer to invoke on onSubscribe
	 *
	 * @return {@literal a new stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> doOnSubscribe(final Consumer<? super Subscription> consumer) {
		return new StreamStateCallback<O>(this, null, consumer);
	}

	/**
	 * Attach a No-Op Stream that only serves the purpose of buffering incoming values if not enough demand is signaled
	 * downstream. A buffering capable stream will prevent underlying dispatcher to be saturated (and sometimes
	 * blocking).
	 *
	 * @return a buffered stream
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> onBackpressureBuffer() {
		return onBackpressureBuffer(ReactiveState.SMALL_BUFFER_SIZE);
	}

	/**
	 * Attach a No-Op Stream that only serves the purpose of buffering incoming values if not enough demand is signaled
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
		return new StreamBarrier<O, O>(this) {
			@Override
			public String getName() {
				return "onBackpressureBuffer";
			}

			@Override
			public void subscribe(Subscriber<? super O> s) {
				Processor<O, O> emitter = Processors.replay(size);
				emitter.subscribe(s);
				source.subscribe(emitter);
			}
		};
	}

	/**
	 * Attach a No-Op Stream that only serves the purpose of dropping incoming values if not enough demand is signaled
	 * downstream. A dropping stream will prevent underlying dispatcher to be saturated (and sometimes blocking).
	 *
	 * @return a dropping stream
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> onBackpressureDrop() {
		return new StreamDrop<>(this);
	}

	/**
	 * Attach a No-Op Stream that only serves the purpose of dropping incoming values if not enough demand is signaled
	 * downstream. A dropping stream will prevent underlying dispatcher to be saturated (and sometimes blocking).
	 *
	 * @return a dropping stream
	 *
	 * @since 2.5
	 */
	public final Stream<O> onBackpressureDrop(Consumer<? super O> onDropped) {
		return new StreamDrop<>(this, onDropped);
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
	 *
	 * @param fallback the error handler for each error
	 *
	 * @return {@literal new Stream}
	 */
	public final Stream<O> onErrorResumeWith(final Function<Throwable, ? extends Publisher<? extends O>> fallback) {
		return new StreamBarrier.Identity<>(new FluxResume<>(this, fallback));
	}

	/**
	 * Produce a default value if any error occurs.
	 *
	 * @param fallback the error handler for each error
	 *
	 * @return {@literal new Stream}
	 */
	public final Stream<O> onErrorReturn(final O fallback) {
		return switchOnError(just(fallback));
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Stream} for each unique key evaluated by the {param
	 * keyMapper}. The hashcode of the incoming data will be used for partitioning over {@link
	 * Processors#DEFAULT_POOL_SIZE} buckets. That means that at any point of time at most {@link
	 * Processors#DEFAULT_POOL_SIZE} number of streams will be created and used accordingly to the current hashcode % n
	 * result.
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values routed to this partition
	 *
	 * @since 2.0
	 */
	public final Stream<GroupedStream<Integer, O>> partition() {
		return partition(Processors.DEFAULT_POOL_SIZE);
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
	 * FIXME Doc
	 */
	@SuppressWarnings("unchecked")
	public final <E> Stream<E> process(final Processor<O, E> processor) {
		subscribe(processor);
		if (Stream.class.isAssignableFrom(processor.getClass())) {
			return (Stream<E>) processor;
		}

		return new StreamBarrier<O, E>(this) {
			@Override
			public String getName() {
				return "process";
			}

			@Override
			public void subscribe(Subscriber s) {
				processor.subscribe(s);
			}
		};
	}

	/**
	 *
	 * @return a subscribed Promise (caching the signal unlike {@link #next)
	 */
	public final Promise<O> promise() {
		Promise<O> p = Promise.prepare();
		p.request(1);
		subscribe(p);
		return p;
	}

	/**
	 *
	 */
	public final Stream<O> publishOn(final ProcessorGroup processorProvider) {
		return new PublishOn<>(this, processorProvider);
	}

	/**
	 * Reduce the values passing through this {@code Stream} into an object {@code T}. This is a simple functional way
	 * for accumulating values. The arguments are the N-1 and N next signal in this order.
	 *
	 * @param fn the reduce function
	 *
	 * @return a new {@link Stream} whose values contain only the reduced objects
	 * @since 1.1, 2.0, 2.5
	 */
	public final Mono<O> reduce(final BiFunction<O, O, O> fn) {
		return scan(fn).last();
	}

	/**
	 * Reduce the values passing through this {@code Stream} into an object {@code A}. The arguments are the N-1 and N
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
	 * Reduce the values passing through this {@code Stream} into an object {@code A}. The arguments are the N-1 and N
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
	 * Create a new {@code Stream} which will keep re-subscribing its oldest parent-child stream pair on complete.
	 *
	 * @return a new infinitely repeated {@code Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> repeat() {
		return repeat(ALWAYS_BOOLEAN_SUPPLIER);
	}

	/**
	 * Create a new {@code Stream} which will keep re-subscribing its oldest parent-child stream pair on complete.
	 *
	 * @param predicate the boolean to evaluate on complete
	 *
	 * @since 2.5
	 */
	public final Stream<O> repeat(BooleanSupplier predicate) {
		return new StreamRepeatPredicate<>(this, predicate);
	}

	/**
	 * Create a new {@code Stream} which will keep re-subscribing its oldest parent-child stream pair on complete. The
	 * action will be propagating complete after {@param numRepeat}. 
	 *
	 * @param numRepeat the number of times to re-subscribe on complete
	 *
	 * @return a new repeated {@code Stream}
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> repeat(final long numRepeat) {
		return new StreamRepeat<O>(this, numRepeat);
	}

	/**
	 * Create a new {@code Stream} which will keep re-subscribing its oldest parent-child stream pair on complete. The
	 * action will be propagating complete after {@param numRepeat}.
	 *
	 * @param numRepeat the number of times to re-subscribe on complete
	 * @param predicate the boolean to evaluate on complete
	 *
	 * @return a new repeated {@code Stream}
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> repeat(final long numRepeat, BooleanSupplier predicate) {
		return new StreamRepeatPredicate<>(this, countingBooleanSupplier(predicate, numRepeat));
	}

	/**
	 * Create a new {@code Stream} which will re-subscribe its oldest parent-child stream pair if the backOff stream
	 * produced by the passed mapper emits any next signal. It will propagate the complete and error if the backoff
	 * stream emits the relative signals.
	 *
	 * @param backOffStream the function providing a stream signalling an anonymous object on each complete
	 * a new stream that applies some backoff policy, e.g. @{link Stream#timer(long)}
	 *
	 * @return a new repeated {@code Stream}
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> repeatWhen(final Function<Stream<?>, ? extends Publisher<?>> backOffStream) {
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
	 * Create a new {@code Stream} which will re-subscribe its oldest parent-child stream pair. The action will start
	 * propagating errors after {@literal Integer.MAX_VALUE}.
	 *
	 * @return a new fault-tolerant {@code Stream}
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final Stream<O> retry() {
		return retry(ALWAYS_PREDICATE);
	}

	/**
	 * Create a new {@code Stream} which will re-subscribe its oldest parent-child stream pair. The action will start
	 * propagating errors after {@param numRetries}. This is generally useful for retry strategies and fault-tolerant
	 * streams.
	 *
	 * @param numRetries the number of times to tolerate an error
	 *
	 * @return a new fault-tolerant {@code Stream}
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> retry(long numRetries) {
		return new StreamRetry<O>(this, numRetries);
	}

	/**
	 * Create a new {@code Stream} which will re-subscribe its oldest parent-child stream pair. {@param retryMatcher}
	 * will test an incoming {@link Throwable}, if positive the retry will occur. This is generally useful for retry
	 * strategies and fault-tolerant streams.
	 *
	 * @param retryMatcher the predicate to evaluate if retry should occur based on a given error signal
	 *
	 * @return a new fault-tolerant {@code Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> retry(Predicate<Throwable> retryMatcher) {
		return new StreamRetryPredicate<>(this, retryMatcher);
	}

	/**
	 * Create a new {@code Stream} which will re-subscribe its oldest parent-child stream pair. The action will start
	 * propagating errors after {@param numRetries}. {@param retryMatcher} will test an incoming {@Throwable}, if
	 * positive the retry will occur (in conjonction with the {@param numRetries} condition). This is generally useful
	 * for retry strategies and fault-tolerant streams.
	 *
	 * @param numRetries the number of times to tolerate an error
	 * @param retryMatcher the predicate to evaluate if retry should occur based on a given error signal
	 *
	 * @return a new fault-tolerant {@code Stream}
	 *
	 * @since 2.0, 2.5
	 */
	public final Stream<O> retry(final long numRetries, final Predicate<Throwable> retryMatcher) {
		return new StreamRetryPredicate<>(this, countingPredicate(retryMatcher, numRetries));
	}

	/**
	 * Create a new {@code Stream} which will re-subscribe its oldest parent-child stream pair if the backOff stream
	 * produced by the passed mapper emits any next data or complete signal. It will propagate the error if the backOff
	 * stream emits an error signal.
	 *
	 * @param backOffStream the function taking the error stream as an downstream and returning a new stream that
	 * applies some backoff policy e.g. Stream.timer
	 *
	 * @return a new fault-tolerant {@code Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> retryWhen(final Function<Stream<Throwable>, ? extends Publisher<?>> backOffStream) {
		return new StreamRetryWhen<O>(this, backOffStream);
	}

	/**
	 * Create a new {@code Stream} whose values will be only the last value of each batch. Requires a {@code
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
	 * Create a new {@code Stream} whose values will be only the last value of each batch.
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
	 * Create a new {@code Stream} whose values will be only the last value of each batch.
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
	 * Create a new {@code Stream} whose values will be only the last value of each batch.
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
	 * Create a new {@code Stream} whose values will be only the first value of each batch. Requires a {@code
	 * getCapacity()} to have been set. <p> When a new batch is triggered, the first value of that next batch will be
	 * pushed into this {@code Stream}.
	 *
	 * @return a new {@link Stream} whose values are the first value of each batch
	 */
	public final Stream<O> sampleFirst() {
		return sampleFirst((int) Math.min(Integer.MAX_VALUE, getCapacity()));
	}

	/**
	 * Create a new {@code Stream} whose values will be only the first value of each batch. <p> When a new batch is
	 * triggered, the first value of that next batch will be pushed into this {@code Stream}.
	 *
	 * @param batchSize the batch size to use
	 *
	 * @return a new {@link Stream} whose values are the first value of each batch)
	 */
	public final Stream<O> sampleFirst(final int batchSize) {
		return new StreamDebounce<O>(this, batchSize, true);
	}

	/**
	 * Create a new {@code Stream} whose values will be only the first value of each batch.
	 *
	 * @param timespan the period in unit to use to release a buffered list
	 * @param unit the time unit
	 *
	 * @return a new {@link Stream} whose values are the first value of each batch
	 */
	public final Stream<O> sampleFirst(long timespan, TimeUnit unit) {
		return sampleFirst(Integer.MAX_VALUE, timespan, unit);
	}

	/**
	 * Create a new {@code Stream} whose values will be only the first value of each batch.
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
	 * Create a new {@code Stream} whose values will be only the first value of each batch.
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
	 * Create a new {@code Stream} whose values will be only the first value signalled after the next {@code other}
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
	 * Scan the values passing through this {@code Stream} into an object {@code A}. The arguments are the N-1 and N
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
	 * Scan the values passing through this {@code Stream} into an object {@code A}. The given initial object will be
	 * passed to the function's {@link Tuple2} argument. Behave like Reduce but triggers downstream Stream for every
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
	 * Create a new {@code Stream} that will NOT signal next elements up to {@param max} times.
	 *
	 * @param max the number of times to drop next signals before starting
	 *
	 * @return a new limited {@code Stream}
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
	 * Create a new {@code Stream} that will NOT signal next elements up to the specified {@param time}.
	 *
	 * @param time the time window to drop next signals before starting
	 * @param unit the time unit to use
	 *
	 * @return a new limited {@code Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> skip(long time, TimeUnit unit) {
		return skip(time, unit, getTimer());
	}
	
	/**
	 * Create a new {@code Stream} that will NOT signal next elements up to the specified {@param time}.
	 *
	 * @param time the time window to drop next signals before starting
	 * @param unit the time unit to use
	 * @param timer the Timer to use
	 *
	 * @return a new limited {@code Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> skip(final long time, final TimeUnit unit, final Timer timer) {
		if (time > 0) {
			Assert.isTrue(timer != null, "Timer can't be found, try assigning an environment to the stream");
			return skipUntil(timer(timer, time, unit));
		}
		else {
			return this;
		}
	}

	/**
	 * Create a new {@code Stream} that WILL NOT signal last {@param n} elements
	 *
	 * @param n the number of elements to ignore before completion
	 *
	 * @return a new limited {@code Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> skipLast(int n) {
		return new StreamSkipLast<>(this, n);
	}

	/**
	 * Create a new {@code Stream} that WILL NOT signal next elements until {@param other} emits.
	 * If {@code other} terminates, then terminate the returned stream and cancel this stream.
	 *
	 * @param other the Publisher to signal when to stop skipping
	 *
	 * @return a new limited {@code Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> skipUntil(final Publisher<?> other) {
		return new StreamSkipUntil<>(this, other);
	}

	/**
	 * Create a new {@code Stream} that WILL NOT signal next elements while {@param limitMatcher} is true
	 *
	 * @param limitMatcher the predicate to evaluate for starting dropping events
	 *
	 * @return a new limited {@code Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> skipWhile(final Predicate<? super O> limitMatcher) {
		return new StreamSkipWhile<>(this, limitMatcher);
	}

	/**
	 * Stage incoming values into a {@link java.util.PriorityQueue<O>} that will be re-ordered and signaled to the
	 * returned fresh {@link Stream}. Possible flush triggers are: {@link #getCapacity()}, complete signal or request
	 * signal. PriorityQueue will use the {@link Comparable<O>} interface from an incoming data signal.
	 *
	 * @return a new {@link Stream} whose values re-ordered using a PriorityQueue.
	 *
	 * @since 2.0
	 */
	public final Stream<O> sort() {
		return sort(null);
	}

	/**
	 * Stage incoming values into a {@link java.util.PriorityQueue<O>} that will be re-ordered and signaled to the
	 * returned fresh {@link Stream}. Possible flush triggers are: {@link #getCapacity()}, complete signal or request
	 * signal. PriorityQueue will use the {@link Comparable<O>} interface from an incoming data signal.
	 *
	 * @param maxCapacity a fixed maximum number or elements to re-order at once.
	 *
	 * @return a new {@link Stream} whose values re-ordered using a PriorityQueue.
	 *
	 * @since 2.0
	 */
	public final Stream<O> sort(int maxCapacity) {
		return sort(maxCapacity, null);
	}

	/**
	 * Stage incoming values into a {@link java.util.PriorityQueue<O>} that will be re-ordered and signaled to the
	 * returned fresh {@link Stream}. Possible flush triggers are: {@link #getCapacity()}, complete signal or request
	 * signal. PriorityQueue will use the {@link Comparable<O>} interface from an incoming data signal.
	 *
	 * @param comparator A {@link Comparator<O>} to evaluate incoming data
	 *
	 * @return a new {@link Stream} whose values re-ordered using a PriorityQueue.
	 *
	 * @since 2.0
	 */
	public final Stream<O> sort(Comparator<? super O> comparator) {
		return sort((int) Math.min(Integer.MAX_VALUE, getCapacity()), comparator);
	}

	/**
	 * Stage incoming values into a {@link java.util.PriorityQueue<O>} that will be re-ordered and signaled to the
	 * returned fresh {@link Stream}. Possible flush triggers are: {@link #getCapacity()}, complete signal or request
	 * signal. PriorityQueue will use the {@link Comparable<O>} interface from an incoming data signal.
	 *
	 * @param maxCapacity a fixed maximum number or elements to re-order at once.
	 * @param comparator A {@link Comparator<O>} to evaluate incoming data
	 *
	 * @return a new {@link Stream} whose values re-ordered using a PriorityQueue.
	 *
	 * @since 2.0
	 */
	public final Stream<O> sort(final int maxCapacity, final Comparator<? super O> comparator) {
		return new StreamSort<O>(this, maxCapacity, comparator);
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
	 */
	public final void subscribe() {
		subscribe(Subscribers.unbounded());
	}

	/**
	 * Create an operation that returns the passed sequence if the Stream has completed without any emitted signals.
	 *
	 * @param fallback an alternate stream if empty
	 *
	 * @return {@literal new Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> switchIfEmpty(final Publisher<? extends O> fallback) {
		return new StreamSwitchIfEmpty<O>(this, fallback);
	}

	/**
	 * Assign the given {@link Function} to transform the incoming value {@code T} into a {@code Stream<O,V>} and pass
	 * it into another {@code Stream}. The produced stream will emit the data from the most recent transformed stream.
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
		return new StreamSwitchMap<>(this, fn, XS_QUEUE_SUPPLIER, XS_BUFFER_SIZE);
	}

	/**
	 * Subscribe to a fallback publisher when any error occurs.
	 *
	 * @param fallback the error handler for each error
	 *
	 * @return {@literal new Stream}
	 */
	public final Stream<O> switchOnError(final Publisher<? extends O> fallback) {
		return new StreamBarrier.Identity<>(new FluxResume<O>(this, new Function<Throwable, Publisher<? extends O>>() {
			@Override
			public Publisher<? extends O> apply(Throwable throwable) {
				return fallback;
			}
		}));
	}

	/**
	 * Create a new {@code Stream} that will signal next elements up to {@param max} times.
	 *
	 * @param max the number of times to broadcast next signals before completing
	 *
	 * @return a new limited {@code Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> take(final long max) {
		return new StreamTake<O>(this, max);
	}

	/**
	 * Create a new {@code Stream} that will signal next elements up to the specified {@param time}.
	 *
	 * @param time the time window to broadcast next signals before completing
	 * @param unit the time unit to use
	 *
	 * @return a new limited {@code Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> take(long time, TimeUnit unit) {
		return take(time, unit, getTimer());
	}

	/**
	 * Create a new {@code Stream} that will signal next elements up to the specified {@param time}.
	 *
	 * @param time the time window to broadcast next signals before completing
	 * @param unit the time unit to use
	 * @param timer the Timer to use
	 *
	 * @return a new limited {@code Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> take(final long time, final TimeUnit unit, final Timer timer) {
		if (time > 0) {
			Assert.isTrue(timer != null, "Timer can't be found, try assigning an environment to the stream");
			return takeUntil(timer(timer, time, unit));
		}
		else {
			return empty();
		}
	}

	/**
	 * Create a new {@code Stream} that will signal the last {@param n} elements.
	 *
	 * @param n the max number of last elements to capture before onComplete
	 *
	 * @return a new limited {@code Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> takeLast(int n) {
		return new StreamTakeLast<>(this, n);
	}

	/**
	 * Create a new {@code Stream} that will signal next elements until {@param limitMatcher} is true.
	 *
	 * @param limitMatcher the predicate to evaluate for starting dropping events and completing
	 *
	 * @return a new limited {@code Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> takeUntil(final Predicate<? super O> limitMatcher) {
		return new StreamTakeUntilPredicate<>(this, limitMatcher);
	}

	/**
	 * Create a new {@code Stream} that will signal next elements until {@param other} emits.
	 * Completion and Error will cause this stream to cancel.
	 *
	 * @param other the {@link Publisher} to signal when to stop replaying signal from upstream
	 *
	 * @return a new limited {@code Stream}
	 *
	 * @since 2.5
	 */
	public final Stream<O> takeUntil(final Publisher<?> other) {
		return new StreamTakeUntil<>(this, other);
	}

	/**
	 * Create a new {@code Stream} that will signal next elements while {@param limitMatcher} is true.
	 *
	 * @param limitMatcher the predicate to evaluate for starting dropping events and completing
	 *
	 * @return a new limited {@code Stream}
	 *
	 * @since 2.0
	 */
	public final Stream<O> takeWhile(final Predicate<? super O> limitMatcher) {
		return new StreamTakeWhile<O>(this, limitMatcher);
	}

	/**
	 * Create a {@link Tap} that maintains a reference to the last value seen by this {@code Stream}. The {@link Tap} is
	 * continually updated when new values pass through the {@code Stream}.
	 *
	 * @return the new {@link Tap}
	 *
	 * @see Consumer
	 */
	public final Tap.Control<O> tap() {
		final Tap<O> tap = Tap.create();
		return new Tap.Control<>(tap, consume(tap));
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
	public final Stream<O> throttle(final long period) {
		final Timer timer = getTimer();
		Assert.state(timer != null, "Cannot use default timer as no environment has been provided to this " + "Stream");

		return new StreamThrottleRequest<O>(this, timer, period);
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
	public final Stream<O> timeout(final long timeout, final TimeUnit unit, final Publisher<? extends O> fallback) {
		final Timer timer = getTimer();
		Assert.state(timer != null, "Cannot use default timer as no environment has been provided to this " + "Stream");

		final Stream<Long> _timer = timer(timer, timeout, unit == null ? TimeUnit.MILLISECONDS : unit);
		final Supplier<Publisher<Long>> first = new Supplier<Publisher<Long>>() {
			@Override
			public Publisher<Long> get() {
				return _timer;
			}
		};
		final Function<O, Publisher<Long>> rest = new Function<O, Publisher<Long>>() {
			@Override
			public Publisher<Long> apply(O o) {
				return _timer;
			}
		};

		return timeout(first, rest, fallback);
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
	public final <U> Stream<O> timeout(final Supplier<? extends Publisher<U>> allTimeout) {
		return timeout(allTimeout, new Function<O, Publisher<U>>() {
			@Override
			public Publisher<U> apply(O o) {
				return allTimeout.get();
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
	public final <U, V> Stream<O> timeout(Supplier<? extends Publisher<U>> firstTimeout,
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
	public final <U, V> Stream<O> timeout(Supplier<? extends Publisher<U>> firstTimeout,
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
		return new StreamBarrier<O, O>(this) {
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
	 * Create a new {@code Stream} that accepts a {@link reactor.fn.tuple.Tuple2} of T1 {@link Long} system time in
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
	 *
	 * {@code stream.to(Processors.queue()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param subscriber
	 * @param <E>
	 *
	 * @return this subscriber
	 */
	public final <E extends Subscriber<? super O>> E to(E subscriber) {
		subscribe(subscriber);
		return subscriber;
	}

	/**
	 * Blocking call to pass values from this stream to the queue that can be polled from a consumer.
	 *
	 * @return the buffered queue
	 *
	 * @since 2.0
	 */
	public final BlockingQueue<O> toBlockingQueue() {
		return toBlockingQueue(ReactiveState.SMALL_BUFFER_SIZE);
	}

	/**
	 * Blocking call to eagerly fetch values from this stream
	 *
	 * @param maximum queue getCapacity(), a full queue might block the stream producer.
	 *
	 * @return the buffered queue
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final BlockingQueue<O> toBlockingQueue(int maximum) {
		return toBlockingQueue(maximum,
				maximum == Integer.MAX_VALUE ? new ConcurrentLinkedQueue<O>() : new ArrayBlockingQueue<O>(maximum));
	}

	/**
	 * Blocking call to eagerly fetch values from this stream
	 *
	 * @param maximum queue getCapacity(), a full queue might block the stream producer.
	 *
	 * @return the buffered queue
	 *
	 * @since 2.0
	 */
	@SuppressWarnings("unchecked")
	public final BlockingQueue<O> toBlockingQueue(int maximum, Queue<O> store) {
		return new BlockingQueueSubscriber<>(this, null, store, false, maximum);
	}

	/**
	 *
	 *
	 * @return
	 */
	public final Iterable<O> toIterable() {
		return toIterable(getCapacity());
	}

	/**
	 *
	 *
	 * @return
	 */
	public final Iterable<O> toIterable(long batchSize) {
		return toIterable(batchSize, null);
	}

	/**
	 *
	 *
	 * @return
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
	public final Mono<List<O>> toList() {
		return buffer(Integer.MAX_VALUE).next();
	}

	@Override
	public String toString() {
		return getClass().getSimpleName();
	}

	/**
	 * Make this Stream subscribers unbounded
	 *
	 * @return Stream with capacity set to max
	 *
	 * @see #capacity(long)
	 */
	public final Stream<O> unbounded() {
		return capacity(Long.MAX_VALUE);
	}

	/**
	 * Assign an error handler to exceptions of the given type. Will not stop error propagation, use when(class,
	 * publisher), retry, ignoreError or recover to actively deal with the error
	 *
	 * @param exceptionType the type of exceptions to handle
	 * @param onError the error handler for each error
	 * @param <E> type of the error to handle
	 *
	 * @return {@literal new Stream}
	 * @since 2.0, 2.5
	 */
	public final <E extends Throwable> Stream<O> when(final Class<E> exceptionType,
			final Consumer<E> onError) {
		return new StreamError<O, E>(this, exceptionType, onError);
	}

	/**
	 * Assign an error handler that will pass eventual associated values and exceptions of the given type. Will not stop
	 * error propagation, use when(class, publisher), retry, ignoreError or recover to actively deal with the error.
	 *
	 * @param exceptionType the type of exceptions to handle
	 * @param onError the error handler for each error
	 * @param <E> type of the error to handle
	 *
	 * @return {@literal new Stream}
	 */
	public final <E extends Throwable> Stream<O> whenErrorValue(final Class<E> exceptionType,
			final BiConsumer<Object, ? super E> onError) {
		return new StreamErrorWithValue<>(this, exceptionType, onError);
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Stream} every pre-defined {@param backlog} times. The
	 * nested streams will be pushed into the returned {@code Stream}.
	 *
	 * @param backlog the time period when each window close and flush the attached consumer
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 *
	 * @since 2.0
	 */
	public final Stream<Stream<O>> window(final int backlog) {
		return new StreamWindow<O>(this, getTimer(), backlog);
	}

	/**
	 * Re-route incoming values into bucket streams that will be pushed into the returned {@code Stream} every {@code
	 * skip} and complete every time {@code maxSize} has been reached by any of them. Complete signal will flush any
	 * remaining buckets.
	 *
	 * @param skip the number of items to skip before creating a new bucket
	 * @param maxSize the collected size
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 */
	public final Stream<Stream<O>> window(final int maxSize, final int skip) {
		if (maxSize == skip) {
			return window(maxSize);
		}
		return new StreamWindowShift<O>(this, getTimer(), maxSize, skip);
	}

	/**
	 * Re-route incoming values into bucket streams that will be pushed into the returned {@code Stream} every  and
	 * complete every time {@code boundarySupplier} stream emits an item.
	 *
	 * @param boundarySupplier the factory to create the stream to listen to for separating each window
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 */
	public final Stream<Stream<O>> window(final Supplier<? extends Publisher<?>> boundarySupplier) {
		return new StreamWindowWhen<O>(this, getTimer(), boundarySupplier);
	}

	/**
	 * Re-route incoming values into bucket streams that will be pushed into the returned {@code Stream} every and
	 * complete every time {@code boundarySupplier} stream emits an item. Window starts forwarding when the
	 * bucketOpening stream emits an item, then subscribe to the boundary supplied to complete.
	 *
	 * @param bucketOpening the publisher to listen for signals to create a new window
	 * @param boundarySupplier the factory to create the stream to listen to for closing an open window
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 */
	public final Stream<Stream<O>> window(final Publisher<?> bucketOpening,
			final Supplier<? extends Publisher<?>> boundarySupplier) {
		return new StreamWindowShiftWhen<O>(this, getTimer(), bucketOpening, boundarySupplier);
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Stream} every pre-defined timespan. The nested streams
	 * will be pushed into the returned {@code Stream}.
	 *
	 * @param timespan the period in unit to use to release a new window as a Stream
	 * @param unit the time unit
	 *
	 * @return a new {@link Stream} whose values are a {@link Stream} of all values in this window
	 *
	 * @since 2.0
	 */
	public final Stream<Stream<O>> window(long timespan, TimeUnit unit) {
		return window(Integer.MAX_VALUE, timespan, unit);
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Stream} every pre-defined timespan OR maxSize items.
	 * The nested streams will be pushed into the returned {@code Stream}.
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
		return new StreamWindow<O>(this, maxSize, timespan, unit, getTimer());
	}

	/**
	 * Re-route incoming values into bucket streams that will be pushed into the returned {@code Stream} every {@code
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
		return new StreamWindowShift<O>(this,
				Integer.MAX_VALUE,
				Integer.MAX_VALUE,
				timespan,
				timeshift,
				unit,
				getTimer());
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
	 * Pass with the passed {@link Publisher} values to a new {@link Stream} until one of them complete. The result will
	 * be produced by the zipper transformation from a tuple of each upstream most recent emitted data.
	 *
	 * @return the zipped stream
	 *
	 * @since 2.0
	 */
	public final <T2, V> Stream<V> zipWith(final Publisher<? extends T2> publisher,
			final BiFunction<? super O, ? super T2, ? extends V> zipper) {
		return new StreamBarrier<O, V>(this) {
			@Override
			public String getName() {
				return "zipWith";
			}

			@Override
			public void subscribe(Subscriber<? super V> s) {
				Flux.zip(source, publisher, zipper).subscribe(s);
			}
		};
	}

	/**
	 * Pass with the passed {@link Publisher} values to a new {@link Stream} until one of them complete. The result will
	 * be produced by the zipper transformation from a tuple of each upstream most recent emitted data.
	 *
	 * @return the zipped stream
	 *
	 * @since 2.5
	 */
	public final <T2> Stream<Tuple2<O, T2>> zipWith(final Publisher<? extends T2> publisher) {
		return new StreamBarrier<O, Tuple2<O, T2>>(this) {
			@Override
			public String getName() {
				return "zipWith";
			}

			@SuppressWarnings("unchecked")
			@Override
			public void subscribe(Subscriber<? super Tuple2<O, T2>> s) {
				Flux.<O, T2, Tuple2<O, T2>>zip(source, publisher, TUPLE2_BIFUNCTION).subscribe(s);
			}
		};
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

	private static final class DispatchOn<O> extends StreamBarrier<O, O> implements FeedbackLoop {

		private final ProcessorGroup processorProvider;

		public DispatchOn(Stream<O> s, ProcessorGroup processorProvider) {
			super(s);
			this.processorProvider = processorProvider;
		}

		@Override
		public Object delegateInput() {
			return processorProvider;
		}

		@Override
		public Object delegateOutput() {
			return processorProvider;
		}

		@Override
		public String getName() {
			return "dispatchOn";
		}

		@Override
		@SuppressWarnings("unchecked")
		public void subscribe(Subscriber s) {
			try {
				Processor<O, O> processor = processorProvider.dispatchOn();
				processor.subscribe(s);
				source.subscribe(processor);
			}
			catch (Throwable t) {
				s.onError(t);
			}
		}
	}

	private static final class PublishOn<O> extends StreamBarrier<O, O> implements ReactiveState.FeedbackLoop {

		final ProcessorGroup processorProvider;

		public PublishOn(Stream<O> s, ProcessorGroup processorProvider) {
			super(s);
			this.processorProvider = processorProvider;
		}

		@Override
		public Object delegateInput() {
			return processorProvider.delegateInput();
		}

		@Override
		public Object delegateOutput() {
			return processorProvider.delegateOutput();
		}

		@Override
		public String getName() {
			return "publishOn";
		}

		@Override
		@SuppressWarnings("unchecked")
		public void subscribe(Subscriber s) {
			try {
				Processor<O, O> processor = processorProvider.publishOn(source);
				processor.subscribe(s);
			}
			catch (Throwable t) {
				s.onError(t);
			}
		}
	}

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

	static final Consumer   NOOP               = new Consumer() {
		@Override
		public void accept(Object o) {

		}
	};
	private static final BiFunction TUPLE2_BIFUNCTION  = new BiFunction() {
		@Override
		public Tuple2 apply(Object t1, Object t2) {
			return Tuple.of(t1, t2);
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

	@SuppressWarnings("unchecked")
	static <O> Supplier<Set<O>> hashSetSupplier() {
		return (Supplier<Set<O>>) SET_SUPPLIER;
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

	static final Stream NEVER = from(Flux.never());
	static final Function IDENTITY_FUNCTION = new Function() {
		@Override
		public Object apply(Object o) {
			return o;
		}
	};
	static final Supplier XS_QUEUE_SUPPLIER = QueueSupplier.get(ReactiveState.XS_BUFFER_SIZE);
}
