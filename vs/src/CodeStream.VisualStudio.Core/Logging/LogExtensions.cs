﻿using CodeStream.VisualStudio.Core.Logging.Instrumentation;
using Serilog;
using Serilog.Events;
using SerilogTimings.Extensions;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using CodeStream.VisualStudio.Core.Extensions;

namespace CodeStream.VisualStudio.Core.Logging {
	public static class LogExtensions {
		public static IDisposable CriticalOperation(this ILogger logger, string message, LogEventLevel logEventLevel = LogEventLevel.Verbose) {
			if (logger == null || !logger.IsEnabled(logEventLevel)) return null;

			return logger.TimeOperation(message, logEventLevel);
		}

		/// <summary>
		/// Defaults to add timings only when in Verbose mode
		/// </summary>
		/// <param name="logger"></param>
		/// <param name="message"></param>
		/// <param name="logEventLevel"></param>
		/// <returns></returns>
		public static IDisposable CriticalOperation(this ILogger logger, Dictionary<string, object> message, LogEventLevel logEventLevel = LogEventLevel.Verbose) {
			if (logger == null || !logger.IsEnabled(logEventLevel)) return null;

			return logger.TimeOperation(message.ToKeyValueString(), logEventLevel);
		}

		static IDisposable TimeOperation(this ILogger log, string message, LogEventLevel logEventLevel = LogEventLevel.Verbose) {
			return log.OperationAt(logEventLevel).Time(message);
		}

		public static bool IsDebugEnabled(this ILogger log) => log.IsEnabled(LogEventLevel.Debug);

		public static bool IsVerboseEnabled(this ILogger log) => log.IsEnabled(LogEventLevel.Verbose);

		public static Metrics WithMetrics(this ILogger log, string message) {
#if DEBUG
			if (!log.IsVerboseEnabled()) return null;

			return new Metrics(log, message);
#else
			return null;
#endif
		}

		[Conditional("DEBUG")]
		public static void LocalWarning(this ILogger logger, string message) {
			logger.Warning($"LOCAL=>{message}");
		}
		
		public static void Ctor(this ILogger logger, string message = null) {
			logger.Debug($"ctor {message}");
		}

		public static void IsNull(this ILogger logger, string message) {
			logger.Warning($"{message} is null");
		}
	}
}
