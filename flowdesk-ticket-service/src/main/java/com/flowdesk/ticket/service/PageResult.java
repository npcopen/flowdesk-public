package com.flowdesk.ticket.service;

import java.util.List;

/** Keyset-pagination result with an opaque next token. */
public record PageResult<T>(List<T> items, String nextPageToken, String sort) {
}
