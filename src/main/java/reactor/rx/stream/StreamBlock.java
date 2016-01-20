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
package reactor.rx.stream;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.queue.Sequencer;
import reactor.core.util.BackpressureUtils;
import reactor.core.util.Exceptions;
import reactor.core.util.Sequence;
import reactor.core.util.WaitStrategy;

/**
 * Drops values if the subscriber doesn't request fast enough.
 *
 * @param <T> the value type
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
public final class StreamBlock<T> extends StreamBarrier<T, T> {

	final WaitStrategy waitStrategy;

	public StreamBlock(Publisher<? extends T> source, WaitStrategy waitStrategy) {
		super(source);
		this.waitStrategy = waitStrategy;
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		source.subscribe(new StreamBlockSubscriber<>(s, waitStrategy));
	}

	static final class StreamBlockSubscriber<T>
			implements Subscriber<T>, Subscription, Downstream, Upstream, ActiveUpstream,
					   DownstreamDemand, ActiveDownstream, Runnable {

		final Subscriber<? super T> actual;

		final WaitStrategy waitStrategy;

		Subscription s;

		@SuppressWarnings("rawtypes")
		static final Sequence REQUESTED = Sequencer.newSequence(0L);

		boolean done;

		volatile boolean cancelled;

		public StreamBlockSubscriber(Subscriber<? super T> actual, WaitStrategy waitStrategy) {
			this.actual = actual;
			this.waitStrategy = waitStrategy;
		}

		@Override
		public void request(long n) {
			if (BackpressureUtils.validate(n)) {
				BackpressureUtils.getAndAdd(REQUESTED, n);
				waitStrategy.signalAllWhenBlocking();
			}
		}

		@Override
		public void cancel() {
			s.cancel();
			cancelled = true;
			waitStrategy.signalAllWhenBlocking();
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (BackpressureUtils.validate(this.s, s)) {
				this.s = s;

				actual.onSubscribe(this);

				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(T t) {

			if (done || cancelled) {
				Exceptions.onNextDropped(t);
				return;
			}

			try {
				for(;;) {
					waitStrategy.waitFor(1L, REQUESTED, this);
					if(BackpressureUtils.getAndSub(REQUESTED, 1L) != 0L) {
						break;
					}
				}
			}
			catch (Exceptions.AlertException | Exceptions.CancelException | InterruptedException ie){
				cancel();
				Exceptions.onNextDropped(t);
			}
			actual.onNext(t);
		}

		@Override
		public void onError(Throwable t) {
			if (done || cancelled) {
				Exceptions.onErrorDropped(t);
				return;
			}
			done = true;

			actual.onError(t);
		}

		@Override
		public void onComplete() {
			if (done || cancelled) {
				return;
			}
			done = true;

			actual.onComplete();
		}

		@Override
		public void run() {
			if(cancelled){
				throw Exceptions.CancelException.INSTANCE;
			}
		}

		@Override
		public boolean isStarted() {
			return s != null && !done;
		}

		@Override
		public boolean isTerminated() {
			return done;
		}

		@Override
		public Object downstream() {
			return actual;
		}

		@Override
		public long requestedFromDownstream() {
			return REQUESTED.get();
		}

		@Override
		public Object upstream() {
			return s;
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}
	}
}
