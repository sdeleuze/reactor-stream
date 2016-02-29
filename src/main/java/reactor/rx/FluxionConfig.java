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

import org.reactivestreams.Publisher;
import reactor.core.flow.Fuseable;
import reactor.core.state.Backpressurable;
import reactor.core.state.Introspectable;
import reactor.core.timer.Timer;

/**
 * @param <T> the value type
 *
 * @since 2.5
 */
final class FluxionConfig<T> extends FluxionSource<T, T> {

	final long   capacity;
	final Timer  timer;
	final String name;

	static <T> Fluxion<T> withCapacity(Publisher<? extends T> source, long capacity) {
		if (source instanceof Fuseable) {
			return new FuseableFluxionConfig<>(source,
					capacity,
					source instanceof Fluxion ? ((Fluxion) source).getTimer() : null,
					source instanceof Introspectable ? ((Introspectable) source).getName() : source.getClass()
					                                                                               .getSimpleName());
		}
		return new FluxionConfig<>(source,
				capacity,
				source instanceof Fluxion ? ((Fluxion) source).getTimer() : null,
				source instanceof Introspectable ? ((Introspectable) source).getName() : source.getClass()
				                                                                               .getSimpleName());
	}

	static <T> Fluxion<T> withTimer(Publisher<? extends T> source, Timer timer) {
		if (source instanceof Fuseable) {
			return new FuseableFluxionConfig<>(source,
					source instanceof Backpressurable ? ((Backpressurable) source).getCapacity() : -1L,
					timer,
					source instanceof Introspectable ? ((Introspectable) source).getName() : source.getClass()
					                                                                               .getSimpleName());
		}
		return new FluxionConfig<>(source,
				source instanceof Backpressurable ? ((Backpressurable) source).getCapacity() : -1L,
				timer,
				source instanceof Introspectable ? ((Introspectable) source).getName() : source.getClass()
				                                                                               .getSimpleName());
	}

	static <T> Fluxion<T> withName(Publisher<? extends T> source, String name) {
		if (source instanceof Fuseable) {
			return new FuseableFluxionConfig<>(source,
					source instanceof Backpressurable ? ((Backpressurable) source).getCapacity() : -1L,
					source instanceof Fluxion ? ((Fluxion) source).getTimer() : null,
					name);
		}
		return new FluxionConfig<>(source,
				source instanceof Backpressurable ? ((Backpressurable) source).getCapacity() : -1L,
				source instanceof Fluxion ? ((Fluxion) source).getTimer() : null,
				name);
	}

	public FluxionConfig(Publisher<? extends T> source, long capacity, Timer timer, String name) {
		super(source);
		this.capacity = capacity;
		this.timer = timer;
		this.name = name;
	}

	@Override
	public Timer getTimer() {
		return timer;
	}

	@Override
	public long getCapacity() {
		return capacity;
	}

	@Override
	public String getName() {
		return name;
	}
}

final class FuseableFluxionConfig<I> extends FluxionSource<I, I> implements Fuseable {

	final long   capacity;
	final Timer  timer;
	final String name;

	public FuseableFluxionConfig(Publisher<? extends I> source, long capacity, Timer timer, String name) {
		super(source);
		this.capacity = capacity;
		this.timer = timer;
		this.name = name;
	}

	@Override
	public Timer getTimer() {
		return timer;
	}

	@Override
	public long getCapacity() {
		return capacity;
	}

	@Override
	public String getName() {
		return name;
	}
}