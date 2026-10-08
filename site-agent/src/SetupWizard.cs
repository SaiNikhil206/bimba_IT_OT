using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Bimba.SiteAgent
{
    /// <summary>
    /// Setup wizard: Welcome -> Gateway -> Sign in (enrollment token or organization user) -> This machine
    /// (what is shared) -> Install -> Done. Enrollment happens on "Install"; the token / password is used once
    /// and never written to disk.
    /// </summary>
    internal sealed class SetupWizard : Form
    {
        private static readonly Color Muted = Color.FromArgb(91, 100, 117);
        private static readonly Color Bad = Color.FromArgb(180, 35, 24);
        private static readonly Color Warn = Color.FromArgb(138, 97, 0);
        private static readonly Color Good = Color.FromArgb(22, 121, 76);

        private readonly Label title = new Label();
        private readonly Label subtitle = new Label();
        private readonly Panel body = new Panel();
        private readonly Button back = new Button();
        private readonly Button next = new Button();
        private readonly Button cancel = new Button();
        private readonly List<Panel> pages = new List<Panel>();
        private int page;
        private bool busy;

        // gateway
        private TextBox server;
        private Label serverNote;
        private CheckBox trustCert;
        private string untrustedCert;
        private string checkedPin;      // certificate pin the gateway check succeeded with (used for the install itself)
        private bool gatewayChecked;

        // sign in
        private RadioButton useToken, useUser;
        private TextBox token, username, password;
        private ComboBox org;
        private Label orgLabel, signInNote;
        private Panel tokenBox, userBox;

        // machine
        private TextBox displayName, site;
        private ListView details;
        private Task<Dictionary<string, object>> inventory;

        // install / done
        private ListBox progress;
        private Label installNote, doneText;
        private AgentConfig installed;

        public SetupWizard()
        {
            Text = "BIMBA Site Agent Setup";
            Font = new Font("Segoe UI", 9.5f);
            AutoScaleMode = AutoScaleMode.Font;
            ClientSize = new Size(660, 520);
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.White;
            try { Icon = Icon.ExtractAssociatedIcon(Installer.SelfPath); } catch { /* default icon */ }

            var header = new Panel { Dock = DockStyle.Top, Height = 78, BackColor = Color.FromArgb(247, 248, 250), Padding = new Padding(24, 14, 24, 10) };
            title.SetBounds(24, 14, 600, 28);
            title.Font = new Font("Segoe UI Semibold", 14f);
            subtitle.SetBounds(24, 44, 600, 22);
            subtitle.ForeColor = Muted;
            header.Controls.Add(title);
            header.Controls.Add(subtitle);
            var line = new Panel { Dock = DockStyle.Top, Height = 1, BackColor = Color.FromArgb(227, 230, 235) };

            var footer = new Panel { Dock = DockStyle.Bottom, Height = 58, BackColor = Color.FromArgb(247, 248, 250) };
            var fline = new Panel { Dock = DockStyle.Bottom, Height = 1, BackColor = Color.FromArgb(227, 230, 235) };
            cancel.Text = "Cancel";
            back.Text = "< Back";
            next.Text = "Next >";
            foreach (var (b, x) in new[] { (back, 340), (next, 438), (cancel, 546) })
            {
                b.SetBounds(x, 14, 92, 30);
                b.FlatStyle = FlatStyle.System;
                footer.Controls.Add(b);
            }
            back.Click += (s, e) => Show(page - 1);
            next.Click += async (s, e) => await Next();
            cancel.Click += (s, e) => Close();
            AcceptButton = next;

            body.Dock = DockStyle.Fill;
            body.Padding = new Padding(28, 18, 28, 10);
            Controls.Add(body);
            Controls.Add(line);
            Controls.Add(header);
            Controls.Add(fline);
            Controls.Add(footer);

            pages.Add(WelcomePage());
            pages.Add(GatewayPage());
            pages.Add(SignInPage());
            pages.Add(MachinePage());
            pages.Add(InstallPage());
            pages.Add(DonePage());
            foreach (var p in pages) { p.Dock = DockStyle.Fill; p.Visible = false; body.Controls.Add(p); }

            inventory = Task.Run(() => Inventory.Collect());
            Show(0);
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (busy) e.Cancel = true;            // never leave a half-done install
            base.OnFormClosing(e);
        }

        // ------------------------------------------------------------------ pages

        private Panel WelcomePage()
        {
            var p = new Panel();
            var text = Para(p, 0, 640,
                "This installs the BIMBA Site Agent on this computer.\n\n" +
                "The agent registers this machine with your organization's BIMBA gateway and then reports every minute " +
                "that it is online. Only your organization can see this machine.\n\n" +
                "It shares basic machine details (name, Windows version, hardware, network addresses and the SCADA software " +
                "found on this computer). It does not read process data, files or user accounts, and it does not change your SCADA system.\n\n" +
                (Preset.Current != null
                    ? "This installer was downloaded from the gateway at " + Preset.Current.Server + ". You need an enrollment token or your organization e-mail address and password."
                    : "You need the gateway address from your BIMBA administrator, and either an enrollment token or your organization e-mail address and password."));
            var existing = AgentConfig.TryLoad();
            if (existing != null || Installer.ServiceStatus() != null)
            {
                var note = Para(p, text.Bottom + 12, 640,
                    existing != null
                        ? $"Already installed: registered to {existing.OrgName} as \"{existing.DisplayName}\" on {existing.Server}. Continuing registers this machine again (it keeps its place in the organization's list)."
                        : "An earlier installation was found. Continuing replaces it.");
                note.ForeColor = Warn;
                var remove = new Button { Text = "Uninstall instead…", FlatStyle = FlatStyle.System };
                remove.SetBounds(0, note.Bottom + 8, 150, 30);
                remove.Click += (s, e) => Uninstall();
                p.Controls.Add(remove);
            }
            return p;
        }

        private Panel GatewayPage()
        {
            var p = new Panel();
            Para(p, 0, 600, "Enter the address of your BIMBA Gateway. Use its HTTPS address, for example\nhttps://gateway.example.com:8443");
            Caption(p, 62, "Gateway address");
            server = new TextBox();
            server.SetBounds(0, 84, 600, 26);
            server.TextChanged += (s, e) => { gatewayChecked = false; untrustedCert = null; trustCert.Visible = false; trustCert.Checked = false; Note(serverNote, "", Muted); };
            p.Controls.Add(server);
            serverNote = Para(p, 120, 600, "");
            trustCert = new CheckBox { Visible = false, AutoSize = false };
            trustCert.SetBounds(0, 190, 600, 44);
            trustCert.CheckedChanged += (s, e) => gatewayChecked = false;
            p.Controls.Add(trustCert);
            var prev = AgentConfig.TryLoad();
            if (Preset.Current != null)
            {
                server.Text = Preset.Current.Server;
                Note(serverNote, "Filled in by the gateway this installer was downloaded from" +
                    (Preset.Current.TrustCert != null ? " (its certificate is trusted automatically)." : "."), Muted);
            }
            else if (prev != null) server.Text = prev.Server;
            return p;
        }

        private Panel SignInPage()
        {
            var p = new Panel();
            Para(p, 0, 600, "How do you want to register this machine? Either way it is added to your organization.");
            useToken = new RadioButton { Text = "Enrollment token (from your BIMBA administrator)", Checked = true };
            useToken.SetBounds(0, 38, 600, 24);
            useUser = new RadioButton { Text = "Organization e-mail address and password" };
            useUser.SetBounds(0, 64, 600, 24);
            p.Controls.Add(useToken);
            p.Controls.Add(useUser);

            tokenBox = new Panel();
            tokenBox.SetBounds(0, 100, 600, 120);
            Caption(tokenBox, 0, "Enrollment token");
            token = new TextBox { UseSystemPasswordChar = true };
            token.SetBounds(0, 22, 600, 26);
            tokenBox.Controls.Add(token);
            var show = new CheckBox { Text = "Show token" };
            show.SetBounds(0, 54, 200, 22);
            show.CheckedChanged += (s, e) => token.UseSystemPasswordChar = !show.Checked;
            tokenBox.Controls.Add(show);
            p.Controls.Add(tokenBox);

            userBox = new Panel { Visible = false };
            userBox.SetBounds(0, 100, 600, 170);
            Caption(userBox, 0, "E-mail address");
            username = new TextBox();
            username.SetBounds(0, 22, 290, 26);
            Caption(userBox, 56, "Password");
            password = new TextBox { UseSystemPasswordChar = true };
            password.SetBounds(0, 78, 290, 26);
            orgLabel = Caption(userBox, 112, "Organization");
            org = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, DisplayMember = "Value", ValueMember = "Key" };
            org.SetBounds(0, 134, 290, 26);
            orgLabel.Visible = org.Visible = false;
            userBox.Controls.AddRange(new Control[] { username, password, org });
            p.Controls.Add(userBox);

            useToken.CheckedChanged += (s, e) => { tokenBox.Visible = useToken.Checked; userBox.Visible = !useToken.Checked; Note(signInNote, "", Muted); };
            username.TextChanged += (s, e) => { org.Visible = orgLabel.Visible = false; org.DataSource = null; };
            signInNote = Para(p, 280, 600, "");
            return p;
        }

        private Panel MachinePage()
        {
            var p = new Panel();
            Caption(p, 0, "Name shown to your organization");
            displayName = new TextBox { Text = Environment.MachineName };
            displayName.SetBounds(0, 22, 290, 26);
            Caption(p, 0, "Site / location (optional)").Left = 310;
            site = new TextBox();
            site.SetBounds(310, 22, 290, 26);
            p.Controls.Add(displayName);
            p.Controls.Add(site);
            Caption(p, 60, "These details are shared with your organization only:");
            details = new ListView { View = View.Details, FullRowSelect = true, HeaderStyle = ColumnHeaderStyle.Nonclickable, GridLines = false };
            details.SetBounds(0, 82, 600, 236);
            details.Columns.Add("Item", 160);
            details.Columns.Add("Value", 415);
            p.Controls.Add(details);
            var prev = AgentConfig.TryLoad();
            if (prev != null) { displayName.Text = prev.DisplayName; site.Text = prev.Site; }
            return p;
        }

        private Panel InstallPage()
        {
            var p = new Panel();
            installNote = Para(p, 0, 600, "Installing…");
            progress = new ListBox { BorderStyle = BorderStyle.None, IntegralHeight = false, SelectionMode = SelectionMode.None };
            progress.SetBounds(0, 34, 600, 280);
            p.Controls.Add(progress);
            return p;
        }

        private Panel DonePage()
        {
            var p = new Panel();
            doneText = Para(p, 0, 600, "");
            return p;
        }

        // ------------------------------------------------------------------ navigation

        private static readonly string[] Titles = { "Welcome", "Gateway", "Sign in", "This machine", "Installing", "Done" };
        private static readonly string[] Subtitles =
        {
            "BIMBA Site Agent " + Inventory.Version,
            "Where this machine registers",
            "Prove this machine belongs to your organization",
            "Check what will be shared",
            "Registering and installing the service",
            "",
        };

        private void Show(int i)
        {
            if (i < 0 || i >= pages.Count) return;
            page = i;
            for (int n = 0; n < pages.Count; n++) pages[n].Visible = n == i;
            title.Text = Titles[i];
            subtitle.Text = i == 5 ? "The machine is registered" : Subtitles[i];
            back.Enabled = i > 0 && i < 4;
            next.Text = i == 3 ? "Install" : i == 5 ? "Finish" : "Next >";
            next.Enabled = i != 4;
            cancel.Enabled = i < 4;
            if (i == 3) FillDetails();
            (i == 1 ? server : i == 2 ? (useToken.Checked ? token : username) : i == 3 ? displayName : (Control)next).Focus();
        }

        private async Task Next()
        {
            if (busy) return;
            switch (page)
            {
                case 0: Show(1); break;
                case 1: if (await CheckGateway()) Show(2); break;
                case 2: if (ValidSignIn()) Show(3); break;
                case 3: await InstallNow(); break;
                case 5: Close(); break;
            }
        }

        private async Task<bool> CheckGateway()
        {
            string url;
            try
            {
                url = GatewayClient.Normalize(server.Text);
            }
            catch (ArgumentException e)
            {
                Note(serverNote, e.Message, Bad);
                return false;
            }
            if (gatewayChecked) return true;
            // not trustCert.Visible: a control on a hidden wizard page reports Visible = false
            string pin = trustCert.Checked && untrustedCert != null ? untrustedCert : Preset.PinFor(url);
            Note(serverNote, "Connecting to " + url + " …", Muted);
            busy = true;
            next.Enabled = false;
            try
            {
                await Task.Run(() => { using (var c = new GatewayClient(url, pin)) c.Check(); });
                if (server.Text != url) server.Text = url;     // (TextChanged clears the trust choice, so set the pin after it)
                checkedPin = pin;
                gatewayChecked = true;
                Note(serverNote, GatewayClient.IsPlainHttpToRemote(url)
                    ? "Connected. Warning: this is plain HTTP, so the token or password travels unencrypted. Prefer the gateway's HTTPS address (port 8443)."
                    : "Connected to the gateway.", GatewayClient.IsPlainHttpToRemote(url) ? Warn : Good);
                if (GatewayClient.IsPlainHttpToRemote(url))
                {
                    var ok = MessageBox.Show(this, "The gateway address uses plain HTTP. Your enrollment token or password will be sent unencrypted.\n\nContinue anyway?",
                        Text, MessageBoxButtons.YesNo, MessageBoxIcon.Warning) == DialogResult.Yes;
                    if (!ok) gatewayChecked = false;
                    return ok;
                }
                return true;
            }
            catch (GatewayException e) when (e.UntrustedCertSha256 != null)
            {
                untrustedCert = e.UntrustedCertSha256;
                Note(serverNote, e.Message + " Only trust it if your administrator confirms this fingerprint.", Bad);
                trustCert.Text = "Trust this gateway certificate. SHA-256 fingerprint:\n" + Fingerprint(untrustedCert);
                trustCert.Visible = true;
                return false;
            }
            catch (GatewayException e)
            {
                Note(serverNote, e.Message, Bad);
                return false;
            }
            finally
            {
                busy = false;
                next.Enabled = true;
            }
        }

        private bool ValidSignIn()
        {
            if (useToken.Checked && string.IsNullOrWhiteSpace(token.Text))
                return Note(signInNote, "Paste the enrollment token.", Bad);
            if (useUser.Checked && (string.IsNullOrWhiteSpace(username.Text) || password.Text.Length == 0))
                return Note(signInNote, "Enter your e-mail address and password.", Bad);
            if (useUser.Checked && org.Visible && org.SelectedItem == null)
                return Note(signInNote, "Choose the organization.", Bad);
            Note(signInNote, "", Muted);
            return true;
        }

        private async void FillDetails()
        {
            details.Items.Clear();
            details.Items.Add(new ListViewItem(new[] { "Collecting…", "" }));
            var m = await inventory;
            details.Items.Clear();
            var os = Json.Obj(m, "os");
            var hw = Json.Obj(m, "hardware");
            void Row(string k, object v) { if (v != null && v.ToString() != "") details.Items.Add(new ListViewItem(new[] { k, v.ToString() })); }
            Row("Computer name", m["hostname"]);
            Row("Domain", m["domain"]);
            Row("Windows", $"{Json.Str(os, "name")} ({Json.Str(os, "version")}, {Json.Str(os, "arch")})");
            Row("Hardware", $"{Json.Str(hw, "manufacturer")} {Json.Str(hw, "model")}".Trim());
            Row("Serial number", Json.Str(hw, "serialNumber"));
            Row("Processor", $"{Json.Str(hw, "cpu")} ({Json.Str(hw, "logicalProcessors")} threads)");
            Row("Memory", Json.Str(hw, "memoryGb") + " GB");
            foreach (var d in (m["disks"] as IEnumerable<object>) ?? Enumerable.Empty<object>())
                if (d is IDictionary<string, object> disk) Row("Disk " + Json.Str(disk, "drive"), $"{Json.Str(disk, "freeGb")} GB free of {Json.Str(disk, "sizeGb")} GB");
            foreach (var n in (m["network"] as IEnumerable<object>) ?? Enumerable.Empty<object>())
                if (n is IDictionary<string, object> nic)
                    Row("Network " + Json.Str(nic, "name"), string.Join(", ", ((IEnumerable<object>)nic["ipv4"]).Concat((IEnumerable<object>)nic["ipv6"])) + "  MAC " + Json.Str(nic, "mac"));
            Row("Time zone", m["timezone"]);
            var scada = ((IEnumerable<object>)m["scada"]).OfType<IDictionary<string, object>>().ToList();
            if (scada.Count == 0) Row("SCADA software", "none detected");
            foreach (var s in scada)
                Row("SCADA software", $"{Json.Str(s, "product")} {Json.Str(s, "version")}{(Json.Str(s, "source") == "service" ? " (service, " + Json.Str(s, "state") + ")" : "")}".Trim());
            Row("Agent", "BIMBA Site Agent " + Inventory.Version);
        }

        private async Task InstallNow()
        {
            if (string.IsNullOrWhiteSpace(displayName.Text)) { displayName.Text = Environment.MachineName; }
            var auth = new Dictionary<string, object>();
            if (useToken.Checked)
            {
                auth["type"] = "token";
                auth["token"] = token.Text.Trim();
            }
            else
            {
                auth["type"] = "password";
                auth["email"] = username.Text.Trim();
                auth["password"] = password.Text;
                if (org.Visible && org.SelectedValue != null) auth["org"] = org.SelectedValue.ToString();
            }
            string url = server.Text, name = displayName.Text.Trim(), where = site.Text.Trim();
            string pin = checkedPin;
            progress.Items.Clear();
            installNote.Text = "Installing…";
            installNote.ForeColor = SystemColors.ControlText;
            Show(4);
            busy = true;
            try
            {
                Step("Registering this machine with " + url);
                installed = await Task.Run(() =>
                {
                    using (var client = new GatewayClient(url, pin))
                    {
                        var enrolled = client.Enroll(auth, name, where, Inventory.Collect(client.Server));
                        var cfg = Installer.ConfigFrom(client, enrolled, where, pin);
                        Invoke((Action)(() => Step($"Registered to {cfg.OrgName}")));
                        Installer.Install(cfg, s => Invoke((Action)(() => Step(s))));
                        return cfg;
                    }
                });
                password.Text = token.Text = "";      // used once, not kept
                Step("Done");
                Log.Info($"installed: agent {installed.AgentId} for {installed.OrgSlug} via {auth["type"]}");
                busy = false;
                doneText.Text = $"This machine is registered to {installed.OrgName} as \"{installed.DisplayName}\".\n\n" +
                                "The BIMBA Site Agent service is running and reports to the gateway every minute. " +
                                "Your organization can see it in BIMBA Operations under Site agents.\n\n" +
                                $"Gateway: {installed.Server}\nAgent id: {installed.AgentId}\nLog: {Log.FilePath}\n\n" +
                                "To remove it later use Settings > Apps (BIMBA Site Agent).";
                Show(5);
            }
            catch (Exception ex)
            {
                busy = false;
                var e = ex is AggregateException a ? a.InnerException : ex;
                Log.Error("install failed: " + e.Message);
                if (e is GatewayException g && (g.Status == 401 || g.Status == 403 || g.Status == 409 || g.Status == 429))
                {
                    if (g.Status == 409)
                    {
                        org.DataSource = g.Orgs;
                        org.SelectedIndex = -1;
                        org.Visible = orgLabel.Visible = true;
                    }
                    Show(2);
                    Note(signInNote, g.Message, Bad);
                    return;
                }
                Step("Failed: " + e.Message);
                installNote.Text = "Installation did not complete. Go back to change the settings, or close and try again.";
                installNote.ForeColor = Bad;
                back.Enabled = cancel.Enabled = true;      // Back returns to "This machine"
            }
        }

        private void Uninstall()
        {
            if (MessageBox.Show(this, "Remove the BIMBA Site Agent from this computer?", Text, MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes)
                return;
            try
            {
                Installer.Uninstall(_ => { }, quiet: false);
                MessageBox.Show(this, "The BIMBA Site Agent was removed.", Text, MessageBoxButtons.OK, MessageBoxIcon.Information);
                Close();
            }
            catch (Exception e)
            {
                MessageBox.Show(this, "Removal failed: " + e.Message, Text, MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        // ------------------------------------------------------------------ small helpers

        private void Step(string text)
        {
            progress.Items.Add("•  " + text);
            progress.TopIndex = progress.Items.Count - 1;
        }

        private static Label Para(Control parent, int top, int width, string text)
        {
            var l = new Label { Text = text, AutoSize = false, MaximumSize = new Size(width, 0) };
            l.SetBounds(0, top, width, 10);
            l.AutoSize = true;
            parent.Controls.Add(l);
            return l;
        }

        private static Label Caption(Control parent, int top, string text)
        {
            var l = new Label { Text = text, AutoSize = true, ForeColor = Muted, Font = new Font("Segoe UI Semibold", 9f) };
            l.Location = new Point(0, top);
            parent.Controls.Add(l);
            return l;
        }

        private static bool Note(Label l, string text, Color color)
        {
            l.Text = text;
            l.ForeColor = color;
            return false;
        }

        private static string Fingerprint(string hex) =>
            string.Join(":", Enumerable.Range(0, hex.Length / 2).Select(i => hex.Substring(i * 2, 2).ToUpperInvariant()));
    }
}
