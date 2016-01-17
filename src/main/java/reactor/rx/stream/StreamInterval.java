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

import java.util.concurrent.TimeUnit;

import org.reactivestreams.Subscriber;
import reactor.core.error.Exceptions;
import reactor.core.timer.Timer;
import reactor.rx.Stream;

/**
 * A Stream that emits {@link 0} after an initial delay and ever incrementing long counter if the period argument is
 * specified.
 * <p>
 * The TimerStream will manage dedicated timers for new subscriber assigned via {@link
 * this#subscribe(Subscriber)}.
 * <p>
 * Create such stream with the provided factory, E.g with a delay of 1 second, then every 2 seconds.:
 * <pre>
 * {@code
 * Streams.delay(1, 2).consume(
 * log::info,
 * log::error,
 * (-> log.info("complete"))
 * )
 * }
 * </pre>
 * <p>
 * Will log:
 * <pre>{@code
 * 0
 * complete
 * }
 * </pre>
 *
 * @author Stephane Maldini
 */
public final class StreamInterval extends Stream<Long> {

	final private long     delay;
	final private long     period;
	final private TimeUnit unit;
	final private Timer    timer;

	public StreamInterval(long delay, long period, TimeUnit unit, Timer timer) {
		this.delay = delay >= 0L ? delay : -1L;
		this.unit = unit != null ? unit : TimeUnit.SECONDS;
		this.period = period;
		this.timer = timer;
	}

	@Override
	public void subscribe(final Subscriber<? super Long> subscriber) {
		try {
			subscriber.onSubscribe(timer.interval(subscriber, period, unit, delay));
		}
		catch (Throwable throwable) {
			Exceptions.throwIfFatal(throwable);
			subscriber.onError(throwable);
		}
	}

	@Override
	public Timer getTimer() {
		return timer;
	}

}
