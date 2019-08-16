﻿using System;
using System.ComponentModel.Composition;
using System.Threading.Tasks;
using CodeStream.VisualStudio.Core.Extensions;
using CodeStream.VisualStudio.Core.Models;
using CodeStream.VisualStudio.Core.Services;
using Microsoft.VisualStudio.Shell;

namespace CodeStream.VisualStudio.Services {
	/// <summary>
	/// Webview settings are saved to something resembling a 'workspace' -- currently, this is
	/// based off of a solution
	/// </summary>
	[Export(typeof(IWebviewUserSettingsService))]
	[PartCreationPolicy(CreationPolicy.Shared)]
	public class WebviewUserSettingsService : UserSettingsService, IWebviewUserSettingsService { 
		[ImportingConstructor]
		public WebviewUserSettingsService([Import(typeof(SVsServiceProvider))] IServiceProvider serviceProvider) : base(serviceProvider) { }

		/// <summary>
		/// Saves to a structure like:
		/// codestream
		///		codestream.{solutionName}.{teamId}
		///			data: dictionary[string, object]
		/// </summary>
		/// <param name="context"></param>
		/// <returns></returns>
		public bool SaveContext(string solutionName, WebviewContext context) {
			if (context == null || context.CurrentTeamId.IsNullOrWhiteSpace()) return false;

			return Save($"{solutionName}.{context.CurrentTeamId}", UserSettingsKeys.WebviewContext, context);
		}

		public WebviewContext TryGetWebviewContext(string solutionName, string teamId) {
			if (teamId.IsNullOrWhiteSpace()) {
				return null;
			}
			return  TryGetValue<WebviewContext>($"{solutionName}.{teamId}", UserSettingsKeys.WebviewContext);
		}

		public bool SaveTeamId(string solutionName, string teamId) {
			if (teamId.IsNullOrWhiteSpace()) return false;

			return Save($"{solutionName}", UserSettingsKeys.TeamId, teamId);
		}

		public string TryGetTeamId(string solutionName) {
			return TryGetValue<string>($"{solutionName}", UserSettingsKeys.TeamId);
		}

		public bool DeleteTeamId(string solutionName) {
			return Save($"{solutionName}", UserSettingsKeys.TeamId, null);
		}
	}
}
