package com.canace.x3schedule.shared;

import com.canace.x3schedule.domain.Models.Instance;
interface HomeActions {
    enum Route {
        TODAY, PENDING, TODOS, NEW, PLANS, NATURAL, SUGGESTIONS, REMINDERS, FILES, CAPACITY, CONNECTION, PRIVACY
    }

    void open(Route route);

    void detail(Instance instance);

    void complete(Instance instance);
}
