package com.obsinity.reference.demodata;

import java.time.Instant;
import java.util.UUID;

record ProfileCandidate(UUID id, Instant stateChangedAt) {}
