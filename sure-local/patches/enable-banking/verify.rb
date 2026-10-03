require "action_dispatch/testing/integration"
require "nokogiri"

expected_database = "sure_enable_banking_check_20260923"
raise "Use the isolated verification database" unless ActiveRecord::Base.connection_db_config.database == expected_database

Rails.logger.level = Logger::FATAL
Rails.cache = ActiveSupport::Cache::MemoryStore.new
ActiveJob::Base.queue_adapter = :test
ActionController::Base.allow_forgery_protection = false

def verify(condition, description)
  raise description unless condition
  puts "PASS: #{description}"
end

def stream_panel(response)
  document = Nokogiri::HTML5.fragment(response.body)
  stream = document.at_css("turbo-stream")
  verify(stream&.[]("action") == "update", "updates panel contents without removing its frame")
  verify(stream["targets"] == "#enable_banking-providers-panel, #enable_banking-connect-form", "targets both the drawer and the settings panel")
  stream
end

ActiveRecord::Base.transaction do
  family = Family.create!(name: "Enable Banking verification", auto_sync_on_login: false)
  password = SecureRandom.hex(24)
  user = User.create!(family: family, email: "enable-banking-check@example.invalid", password: password, role: :admin, onboarded_at: Time.current)
  client = ActionDispatch::Integration::Session.new(Rails.application)
  client.host! "localhost"
  client.https!
  client.post "/sessions", params: { email: user.email, password: password }
  verify(client.response.redirect? && user.sessions.count == 1, "test user authenticates in the isolated database")

  headers = { "Accept" => "text/vnd.turbo-stream.html" }
  attributes = { country_code: "", application_id: "verification-application", client_certificate: OpenSSL::PKey::RSA.new(2048).to_pem }
  client.post "/enable_banking_items", params: { enable_banking_item: attributes }, headers: headers
  verify(client.response.status == 422 && EnableBankingItem.count.zero?, "blank country is rejected without creating a connection")
  panel = stream_panel(client.response)
  verify(panel.text.include?("Country code"), "validation error is visible in the Turbo response")
  verify(panel.at_css("input[name='enable_banking_item[application_id]']")["value"] == attributes[:application_id], "application ID survives a failed submission")
  verify(panel.at_css("textarea").text.strip == attributes[:client_certificate].strip, "entered key survives a failed submission")
  verify(panel.at_css("select[name='enable_banking_item[country_code]']").key?("required"), "browser requires country selection")

  client.post "/enable_banking_items", params: { enable_banking_item: attributes }, headers: headers.merge("Turbo-Frame" => "enable_banking-connect-form")
  verify(client.response.status == 422, "repeated invalid submission still displays an error")
  stream_panel(client.response)

  client.post "/enable_banking_items", params: { enable_banking_item: attributes }, headers: { "Accept" => "text/html" }
  verify(client.response.status == 303, "plain HTML validation failure uses an actual redirect")
  client.follow_redirect!
  verify(client.response.status == 200 && client.response.body.include?("Country code"), "plain HTML redirect displays the error")

  attributes[:country_code] = "ES"
  client.post "/enable_banking_items", params: { enable_banking_item: attributes }, headers: headers
  verify(client.response.status == 303 && EnableBankingItem.count == 1, "valid drawer submission saves and redirects")
  client.follow_redirect!
  verify(client.response.status == 200 && client.response.body.include?("enable_banking-providers-panel"), "saved connection appears in settings")

  item = EnableBankingItem.first
  client.patch "/enable_banking_items/#{item.id}", params: { enable_banking_item: { country_code: "", application_id: "retained-edit" } }, headers: headers
  verify(client.response.status == 422 && item.reload.country_code == "ES", "invalid edit leaves saved configuration intact")
  panel = stream_panel(client.response)
  verify(panel.at_css("input[name='enable_banking_item[application_id]']")["value"] == "retained-edit", "failed edit retains submitted values")

  client.patch "/enable_banking_items/#{item.id}", params: { enable_banking_item: { country_code: "ES" } }, headers: headers.merge("Turbo-Frame" => "enable_banking-providers-panel")
  verify(client.response.status == 200 && item.reload.country_code == "ES", "valid frame edit succeeds")
  stream_panel(client.response)

  second_attributes = attributes.merge(application_id: "second-verification-application")
  client.post "/enable_banking_items", params: { enable_banking_item: second_attributes }, headers: headers.merge("Turbo-Frame" => "enable_banking-connect-form")
  verify(client.response.status == 200 && EnableBankingItem.count == 2, "valid frame creation succeeds")
  stream_panel(client.response)

  client.get "/settings/providers", headers: { "Accept" => "text/html" }
  verify(client.response.body.include?("https://localhost/enable_banking_items/callback"), "local callback has the correct scheme and path")
  client.host! "sure.pausa.internal"
  client.post "/sessions", params: { email: user.email, password: password }
  verify(client.response.redirect? && user.sessions.count == 2, "private hostname has its own authenticated browser session")
  client.get "/settings/providers", headers: { "Accept" => "text/html" }
  verify(client.response.body.include?("https://sure.pausa.internal/enable_banking_items/callback"), "WARP callback uses HTTPS and the private hostname")

  anonymous = ActionDispatch::Integration::Session.new(Rails.application)
  anonymous.host! "localhost"
  anonymous.https!
  anonymous.post "/enable_banking_items", params: { enable_banking_item: attributes }, headers: headers
  verify(anonymous.response.redirect? && EnableBankingItem.count == 2, "anonymous requests cannot configure banking")

  raise ActiveRecord::Rollback
end

verify(User.count.zero? && EnableBankingItem.count.zero?, "verification records are rolled back")
