# This file is auto-generated from the current state of the database. Instead
# of editing this file, please use the migrations feature of Active Record to
# incrementally modify your database, and then regenerate this schema definition.
#
# This file is the source Rails uses to define your schema when running `bin/rails
# db:schema:load`. When creating a new database, `bin/rails db:schema:load` tends to
# be faster and is potentially less error prone than running all of your
# migrations from scratch. Old migrations may fail to apply correctly if those
# migrations use external dependencies or application code.
#
# It's strongly recommended that you check this file into your version control system.

ActiveRecord::Schema[8.1].define(version: 0) do
  # These are extensions that must be enabled in order to support this database
  enable_extension "pg_catalog.plpgsql"

  create_table "double_entry_account_balances", force: :cascade do |t|
    t.string "account", null: false
    t.string "scope"
    t.bigint "balance", null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.index ["account"], name: "index_account_balances_on_account"
    t.index ["scope", "account"], name: "index_account_balances_on_scope_and_account", unique: true
  end

  create_table "double_entry_line_checks", force: :cascade do |t|
    t.bigint "last_line_id", null: false
    t.boolean "errors_found", null: false
    t.text "log"
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.index ["created_at", "last_line_id"], name: "line_checks_created_at_last_line_id_idx"
  end

  create_table "double_entry_line_metadata", force: :cascade do |t|
    t.bigint "line_id", null: false
    t.string "key", null: false
    t.string "value", null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.index ["line_id", "key", "value"], name: "lines_meta_line_id_key_value_idx"
  end

  create_table "double_entry_lines", force: :cascade do |t|
    t.string "account", null: false
    t.string "scope"
    t.string "code", null: false
    t.bigint "amount", null: false
    t.bigint "balance", null: false
    t.bigint "partner_id"
    t.string "partner_account", null: false
    t.string "partner_scope"
    t.string "detail_type"
    t.bigint "detail_id"
    t.jsonb "metadata"
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.index ["account", "code", "created_at", "partner_account"], name: "lines_account_code_created_at_partner_account_idx"
    t.index ["account", "created_at"], name: "lines_account_created_at_idx"
    t.index ["scope", "account", "created_at"], name: "lines_scope_account_created_at_idx"
    t.index ["scope", "account", "id"], name: "lines_scope_account_id_idx"
  end

  create_table "users", force: :cascade do |t|
    t.string "username", null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.index ["username"], name: "index_users_on_username", unique: true
  end
end
