package com.obsinity.service.core.state.transition.counter;

import java.util.List;

public interface TransitionCounterPostingIdRepository {
    List<TransitionCounterPosting> filterNew(List<TransitionCounterPosting> postings);
}
