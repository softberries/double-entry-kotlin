# Ruby side of MixedWritersTest (M-016, D-017), modelled on script/jack_hammer (threads mode).
#
# MODE=transfer (default): THREADS threads make TRANSFERS random transfers in total between the shared accounts.
#   Every thread waits on the shared advisory lock GATE_LOCK before its first transfer, so the Kotlin test starts
#   both writers at the same moment by releasing its exclusive lock. With FLUSH_AT_LINES=500,1000,... a flusher
#   thread deletes every balance row (as jack_hammer's flush) as soon as the ledger holds that many lines, whoever
#   wrote them, so the flushes land while both writers are running.
# MODE=check: DoubleEntry::Validation::LineCheck.perform! over the database.
#
# Prints `READY` once connected, and a one-line `SUMMARY {json}` at the end. Exit code 1 if any transfer failed.
require 'bundler/setup'
require 'json'
require 'logger'
require 'active_record'
require 'double_entry'
# jsonb metadata, the only mode the Kotlin port supports (D-004); must be set before the Line model loads.
DoubleEntry.config.json_metadata = true
require 'double_entry/line'

Money.locale_backend = :i18n
Money.default_currency = Money::Currency.new('USD')
Money.rounding_mode = BigDecimal::ROUND_HALF_EVEN

MODE = ENV.fetch('MODE', 'transfer')
THREADS = Integer(ENV.fetch('THREADS', '4'))
TRANSFERS = Integer(ENV.fetch('TRANSFERS', '500'))
SEED = Integer(ENV.fetch('SEED', '20141013'))
GATE_LOCK = Integer(ENV.fetch('GATE_LOCK', '0'))
DETAIL_ID = Integer(ENV.fetch('DETAIL_ID', '1'))
FLUSH_AT_LINES = ENV.fetch('FLUSH_AT_LINES', '').split(',').map { |n| Integer(n) }
CODE = :test

ActiveRecord::Base.establish_connection(ENV.fetch('DATABASE_URL') + "?pool=#{THREADS + 4}")
ActiveRecord::Base.logger = Logger.new($stderr, level: Logger::WARN)

# The polymorphic detail of the transfers; the Kotlin side writes DetailRef("User", DETAIL_ID).
class User < ActiveRecord::Base; end

# The shared account set; MixedWritersConfiguration defines the same in Kotlin.
SCOPED = %w[account-0 account-1 account-2].freeze
UNSCOPED = %w[account-3 account-4].freeze
DoubleEntry.configure do |config|
  config.define_accounts do |accounts|
    identity = ->(x) { x }
    SCOPED.each { |id| accounts.define(identifier: id.to_sym, scope_identifier: identity) }
    UNSCOPED.each { |id| accounts.define(identifier: id.to_sym) }
  end
  config.define_transfers do |transfers|
    (SCOPED + UNSCOPED).product(SCOPED + UNSCOPED).each do |from, to|
      transfers.define(from: from.to_sym, to: to.to_sym, code: CODE)
    end
  end
end
ACCOUNTS = SCOPED.map { |id| DoubleEntry.account(id.to_sym, scope: 1) } + UNSCOPED.map { |id| DoubleEntry.account(id.to_sym) }

EVENTS = Hash.new(0)
EVENTS_MUTEX = Mutex.new
ActiveSupport::Notifications.subscribe(/double_entry/) do |name, *|
  EVENTS_MUTEX.synchronize { EVENTS[name.delete_suffix('.double_entry')] += 1 }
end

def summary(fields)
  puts "SUMMARY #{JSON.generate({ writer: 'ruby', mode: MODE, events: EVENTS.dup }.merge(fields))}"
  $stdout.flush
end

def with_connection(&block) = ActiveRecord::Base.connection_pool.with_connection(&block)

def line_count = with_connection { |c| c.select_value('SELECT COUNT(*) FROM double_entry_lines').to_i }

def run_check
  puts 'READY'
  $stdout.flush
  check = DoubleEntry::Validation::LineCheck.perform!
  summary(line_check: check && { errors_found: check.errors_found, last_line_id: check.last_line_id, log: check.log })
  exit(check.nil? || !check.errors_found ? 0 : 1)
end

# Waits for the Kotlin lines too, so it ends once every threshold is reached, a Ruby worker failed, or after 5 minutes.
def run_flusher(errors)
  flushes = []
  pending = FLUSH_AT_LINES.dup
  deadline = Process.clock_gettime(Process::CLOCK_MONOTONIC) + 300
  until pending.empty? || !errors.empty? || Process.clock_gettime(Process::CLOCK_MONOTONIC) > deadline
    lines = line_count
    if lines >= pending.first
      # A multi-row DELETE locks rows in physical order while writers lock them in account order, so a flush during
      # writes can deadlock (jack_hammer never shows this: it flushes between batches). PostgreSQL then aborts one
      # side; a writer restarts its lock transaction (BR-067), the flusher retries here.
      deadlocks = 0
      begin
        deleted = DoubleEntry::AccountBalance.delete_all
      rescue ActiveRecord::Deadlocked
        deadlocks += 1
        retry
      end
      flushes << { at_lines: lines, deleted: deleted, deadlock_retries: deadlocks }
      pending.shift
    else
      sleep 0.005
    end
  end
  flushes
end

def run_transfers
  user = User.find(DETAIL_ID)
  errors = Queue.new
  puts 'READY'
  $stdout.flush
  started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
  workers = Array.new(THREADS) do |worker|
    Thread.new do
      with_connection do |c|
        c.execute("SELECT pg_advisory_lock_shared(#{GATE_LOCK})")
        c.execute("SELECT pg_advisory_unlock_shared(#{GATE_LOCK})")
      end
      random = Random.new(SEED + worker)
      iterations = TRANSFERS / THREADS + (worker < TRANSFERS % THREADS ? 1 : 0)
      iterations.times do |seq|
        from = ACCOUNTS.sample(random: random)
        to = (ACCOUNTS - [from]).sample(random: random)
        DoubleEntry.transfer(
          Money.new(random.rand(1000) + 1),
          from: from, to: to, code: CODE,
          detail: seq.even? ? user : nil,
          metadata: { writer: 'ruby', worker: worker, seq: seq, tags: %w[mixed d-017] },
        )
      end
    rescue StandardError => e
      errors << "#{e.class}: #{e.message}"
    end
  end
  flusher = Thread.new { run_flusher(errors) }
  workers.each(&:join)
  flushes = flusher.value
  failures = Array.new(errors.size) { errors.pop }
  summary(threads: THREADS, transfers: TRANSFERS, errors: failures, flushes: flushes,
          elapsed_ms: ((Process.clock_gettime(Process::CLOCK_MONOTONIC) - started) * 1000).round)
  exit(failures.empty? ? 0 : 1)
end

MODE == 'check' ? run_check : run_transfers
