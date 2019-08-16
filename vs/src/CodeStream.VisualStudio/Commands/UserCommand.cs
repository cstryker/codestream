﻿using CodeStream.VisualStudio.Core.Logging;
using Microsoft.VisualStudio.Shell;
using System;
using CodeStream.VisualStudio.Core.Extensions;
using CodeStream.VisualStudio.Core.Models;
using CodeStream.VisualStudio.Core.Services;
using CodeStream.VisualStudio.Core.Vssdk.Commands;
using Serilog;

namespace CodeStream.VisualStudio.Commands {
	public class UserCommand : VsCommandBase {
		private static readonly ILogger Log = LogManager.ForContext<UserCommand>();

		private const string DefaultText = "Sign In...";
		private readonly ISessionService _sessionService;
		private readonly ISettingsManager _settingsManager;

		private static bool DefaultVisibility = false;

		public UserCommand(ISessionService sessionService, ISettingsManager settingManager) : base(PackageGuids.guidWebViewPackageCmdSet, PackageIds.UserCommandId) {
			_sessionService = sessionService;
			_settingsManager = settingManager;

#if DEBUG
			// make this visible in DEUBG so we can see the Developer tools command
			DefaultVisibility = true;
#endif
			Visible = DefaultVisibility;
			Enabled = DefaultVisibility;
			Text = DefaultText;
		}

		public void Update() {
			ThreadHelper.ThrowIfNotOnUIThread();
			var state = _sessionService.SessionState;
			var agentReady = _sessionService.IsAgentReady;
			Log.Debug($"Updating {nameof(UserCommand)} SessionState={_sessionService.SessionState} AgentReady={agentReady}...");

			if (!agentReady) {
				Visible = false;
				Enabled = false;
				Text = DefaultText;
				return;
			}
			
			try {
				switch (state) {
					case SessionState.UserSignInFailed: {
							// the caching on this sucks and it doesn't always update...
							//Visible = false;
							//Enabled = false;
							//Text = DefaultText;							
							break;
						}
					case SessionState.UserSigningIn:
					case SessionState.UserSigningOut: {
							// the caching on this sucks and it doesn't always update...
							//if (!_sessionService.IsReady) {
							//	Text = "Loading...";
							//	Visible = false;
							//	Enabled = false;
							//}							
							break;
						}
					case SessionState.UserSignedIn: {
							var user = _sessionService.User;
							var env = _settingsManager?.GetUsefulEnvironmentName();
							var label = env.IsNullOrWhiteSpace() ? user.UserName : $"{env}: {user.UserName}";
							
							Visible = true;
							Enabled = true;
							Text = user.HasSingleTeam() ? label : $"{label} - {user.TeamName}";
							break;
						}
					default: {
							Visible = false;
							Enabled = false;
							Text = DefaultText;
							break;
						}
				}
			}
			catch (Exception ex) {
				Log.Error(ex, nameof(UserCommand));
			}
		}

		protected override void ExecuteUntyped(object parameter) {
			//noop
		}
	}
}
