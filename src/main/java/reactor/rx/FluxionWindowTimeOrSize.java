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

import java.util.concurrent.TimeUnit;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Loopback;
import reactor.core.flow.Producer;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.FluxProcessor;
import reactor.core.timer.Timer;
import reactor.core.util.PlatformDependent;

/**
 * WindowAction is forwarding events on a steam until {@param backlog} is reached, after that streams collected events
 * further, complete it and create a fresh new stream.
 * @author Stephane Maldini
 * @since 2.0, 2.5
 */
final class FluxionWindowTimeOrSize<T> extends FluxionBatch<T, Fluxion<T>> {

	protected final Timer timer;

	public FluxionWindowTimeOrSize(Publisher<T> source, Timer timer, int backlog) {
		super(source, backlog, true, true, true);
		this.timer = timer;
	}

	public FluxionWindowTimeOrSize(Publisher<T> source, int backlog, long timespan, TimeUnit unit, Timer timer) {
		super(source, backlog, true, true, true, timespan, unit, timer);
		this.timer = timer;
	}

	@Override
	public Subscriber<? super T> apply(Subscriber<? super Fluxion<T>> subscriber) {
		return new WindowAction<>(prepareSub(subscriber), batchSize, timespan, unit, timer);
	}

	final static class Window<T> extends Fluxion<T> implements Subscriber<T>, Subscription, Producer {

		final protected FluxProcessor<T, T> processor;
		final protected Timer               timer;

		protected int count = 0;

		public Window(Timer timer) {
			this(timer, PlatformDependent.SMALL_BUFFER_SIZE);
		}

		public Window(Timer timer, int size) {
			this.processor = EmitterProcessor.create(size);
			this.processor.onSubscribe(this);
			this.timer = timer;
		}

		@Override
		public Timer getTimer() {
			return timer;
		}

		@Override
		public long getCapacity() {
			return processor.getCapacity();
		}

		@Override
		public void onSubscribe(Subscription s) {
			s.cancel();
		}

		@Override
		public void onNext(T t) {
			count++;
			processor.onNext(t);
		}

		@Override
		public void onError(Throwable t) {
			processor.onError(t);
		}

		@Override
		public void onComplete() {
			processor.onComplete();
		}

		@Override
		public void subscribe(Subscriber<? super T> s) {
			processor.subscribe(s);
		}

		@Override
		public void request(long n) {

		}

		@Override
		public void cancel() {

		}

		@Override
		public Object downstream() {
			return processor;
		}

		@Override
		public int getMode() {
			return INNER;
		}
	}

	final static class WindowAction<T> extends BatchAction<T, Fluxion<T>> implements Loopback {

		private final Timer timer;

		private Window<T> currentWindow;

		public WindowAction(Subscriber<? super Fluxion<T>> actual,
				int backlog,
				long timespan,
				TimeUnit unit,
				Timer timer) {

			super(actual, backlog, true, true, true, timespan, unit, timer);
			this.timer = timer;
		}

		protected Fluxion<T> createWindowStream() {
			Window<T> _currentWindow = new Window<T>(timer);
			_currentWindow.onSubscribe(new Subscription(){

				@Override
				public void cancel() {
					currentWindow = null;
				}

				@Override
				public void request(long n) {

				}
			});
			currentWindow = _currentWindow;
			return _currentWindow;
		}

		@Override
		protected void checkedError(Throwable ev) {
			if (currentWindow != null) {
				currentWindow.onError(ev);
			}
			super.checkedError(ev);
		}

		@Override
		protected void checkedComplete() {
			try {
				if (currentWindow != null) {
					currentWindow.onComplete();
					currentWindow = null;
				}
			}
			finally {
				super.checkedComplete();
			}
		}

		@Override
		protected void firstCallback(T event) {
			subscriber.onNext(createWindowStream());
		}

		@Override
		protected void nextCallback(T event) {
			if (currentWindow != null) {
				currentWindow.onNext(event);
			}
		}

		@Override
		protected void flushCallback(T event) {
			if (currentWindow != null) {
				currentWindow.onComplete();
				//currentWindow = null;
			}
		}

		@Override
		public Object connectedInput() {
			return currentWindow;
		}

		@Override
		public Object connectedOutput() {
			return null;
		}
	}


}
