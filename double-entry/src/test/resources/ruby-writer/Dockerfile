# Ruby writer for MixedWritersTest (M-016, D-017): the double_entry gem at the pinned commit, with the investigator's
# gems, writing to the PostgreSQL given in DATABASE_URL. U-011: pin the fork commit; bump it (and rebuild) to track
# upstream fixes.
FROM ruby:3.3

ARG DOUBLE_ENTRY_REPO=https://github.com/softberries/double_entry.git
ARG DOUBLE_ENTRY_COMMIT=f1474f0fe160edadd3b3264397b85d38d3a7751d

WORKDIR /app
# The gemspec lists its files with `git ls-files`, so the checkout must stay a git repository.
RUN git init -q src \
 && git -C src remote add origin "$DOUBLE_ENTRY_REPO" \
 && git -C src fetch -q --depth 1 origin "$DOUBLE_ENTRY_COMMIT" \
 && git -C src checkout -q FETCH_HEAD

# mysql2 (gemspec development group) needs libmysqlclient and is not used: PostgreSQL only.
ENV BUNDLE_GEMFILE=/app/tools/Gemfile \
    BUNDLE_WITHOUT=gemspec_dev \
    BUNDLE_JOBS=4
COPY Gemfile Gemfile.investigator /app/tools/
RUN bundle install --quiet

# Last, so editing the writer does not rebuild the gem layers.
COPY writer.rb /app/tools/
CMD ["bundle", "exec", "ruby", "/app/tools/writer.rb"]
