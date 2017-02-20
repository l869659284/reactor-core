/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

package reactor.core.publisher;

import java.util.Objects;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.util.context.Context;

/**
 * Defers the creation of the actual Publisher the Subscriber will be subscribed to.
 *
 * @param <T> the value type
 *
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class FluxDefer<T> extends Flux<T> {

	final Supplier<? extends Publisher<? extends T>> supplier;

	FluxDefer(Supplier<? extends Publisher<? extends T>> supplier) {
		this.supplier = Objects.requireNonNull(supplier, "supplier");
	}

	@Override
	@SuppressWarnings("unchecked")
	public void subscribe(Subscriber<? super T> s, Context ctx) {
		Publisher<? extends T> p;

		try {
			p = Objects.requireNonNull(supplier.get(),
					"The Publisher returned by the supplier is null");
		}
		catch (Throwable e) {
			Operators.error(s, Operators.onOperatorError(e));
			return;
		}

		if (p instanceof ContextualPublisher) {
			((ContextualPublisher<T>) p).subscribe(s, ctx);
		}
		else{
			p.subscribe(s);
		}
	}
}
