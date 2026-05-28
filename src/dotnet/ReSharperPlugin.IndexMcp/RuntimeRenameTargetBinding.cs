using System;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;

namespace ReSharperPlugin.IndexMcp;

internal enum RuntimeRenameTargetBindingKind
{
    Constructor,
    PropertySetter,
    ContextOnly,
    Unsupported
}

internal sealed class RuntimeRenameTargetBindingResult
{
    public RuntimeRenameTargetBindingResult(object? provider, RuntimeRenameTargetBindingKind kind, string? failureMessage)
    {
        Provider = provider;
        Kind = kind;
        FailureMessage = failureMessage;
    }

    public object? Provider { get; }
    public RuntimeRenameTargetBindingKind Kind { get; }
    public string? FailureMessage { get; }
    public bool IsSupported => Provider != null && Kind != RuntimeRenameTargetBindingKind.Unsupported;
}

internal static class RuntimeRenameTargetBinding
{
    public static RuntimeRenameTargetBindingResult Bind(Type providerType, object target, string newName)
    {
        ArgumentNullException.ThrowIfNull(providerType);
        ArgumentNullException.ThrowIfNull(target);
        ArgumentNullException.ThrowIfNull(newName);

        var failureReasons = new List<string>();
        if (TryCreateTargetBoundProvider(providerType, target, newName, out var constructorProvider, out var constructorFailure))
            return new RuntimeRenameTargetBindingResult(constructorProvider, RuntimeRenameTargetBindingKind.Constructor, null);
        if (!string.IsNullOrWhiteSpace(constructorFailure))
            failureReasons.Add(constructorFailure!);

        if (!TryCreateNameOnlyProvider(providerType, newName, out var provider, out var providerFailure))
        {
            if (!string.IsNullOrWhiteSpace(providerFailure))
                failureReasons.Add(providerFailure!);
            return Unsupported(providerType, failureReasons);
        }

        if (TryBindRenameTargetProperty(provider, target, out var setterFailure))
            return new RuntimeRenameTargetBindingResult(provider, RuntimeRenameTargetBindingKind.PropertySetter, null);

        if (!string.IsNullOrWhiteSpace(setterFailure))
            failureReasons.Add(setterFailure!);

        return new RuntimeRenameTargetBindingResult(provider, RuntimeRenameTargetBindingKind.ContextOnly, null);
    }

    private static RuntimeRenameTargetBindingResult Unsupported(Type providerType, IEnumerable<string> failureReasons)
    {
        var details = string.Join(" | ", failureReasons.Where(reason => !string.IsNullOrWhiteSpace(reason)));
        var message =
            $"File rename is fail-closed because {providerType.FullName} does not expose a compatible headless target-binding shape. " +
            "Probed constructor(target, newName), writable RenameTarget, and context-only workflow initialization.";
        if (!string.IsNullOrWhiteSpace(details))
            message = $"{message} Details: {details}";
        return new RuntimeRenameTargetBindingResult(null, RuntimeRenameTargetBindingKind.Unsupported, message);
    }

    private static bool TryCreateTargetBoundProvider(Type providerType, object target, string newName, out object? provider, out string? failureReason)
    {
        provider = null;
        failureReason = null;

        var constructor = providerType
            .GetConstructors(BindingFlags.Instance | BindingFlags.Public)
            .FirstOrDefault(candidate =>
            {
                var parameters = candidate.GetParameters();
                return parameters.Length == 2 &&
                       parameters[1].ParameterType == typeof(string) &&
                       parameters[0].ParameterType.IsInstanceOfType(target);
            });

        if (constructor == null)
        {
            failureReason = "constructor(target, newName) not available";
            return false;
        }

        try
        {
            provider = constructor.Invoke(new[] { target, newName });
            return true;
        }
        catch (TargetInvocationException ex) when (ex.InnerException != null)
        {
            failureReason = $"constructor(target, newName) threw {ex.InnerException.GetType().Name}: {ex.InnerException.Message}";
            return false;
        }
        catch (Exception ex)
        {
            failureReason = $"constructor(target, newName) failed: {ex.GetType().Name}: {ex.Message}";
            return false;
        }
    }

    private static bool TryCreateNameOnlyProvider(Type providerType, string newName, out object? provider, out string? failureReason)
    {
        provider = null;
        failureReason = null;

        var constructor = providerType.GetConstructor(BindingFlags.Instance | BindingFlags.Public, null, new[] { typeof(string) }, null);
        if (constructor == null)
        {
            failureReason = "constructor(newName) not available";
            return false;
        }

        try
        {
            provider = constructor.Invoke(new object[] { newName });
            return true;
        }
        catch (TargetInvocationException ex) when (ex.InnerException != null)
        {
            failureReason = $"constructor(newName) threw {ex.InnerException.GetType().Name}: {ex.InnerException.Message}";
            return false;
        }
        catch (Exception ex)
        {
            failureReason = $"constructor(newName) failed: {ex.GetType().Name}: {ex.Message}";
            return false;
        }
    }

    private static bool TryBindRenameTargetProperty(object provider, object target, out string? failureReason)
    {
        failureReason = null;
        var property = provider.GetType().GetProperty("RenameTarget", BindingFlags.Instance | BindingFlags.Public);
        if (property == null)
        {
            failureReason = "writable RenameTarget property not available";
            return false;
        }

        if (!property.CanWrite)
        {
            failureReason = "RenameTarget property is read-only";
            return false;
        }

        if (!property.PropertyType.IsInstanceOfType(target))
        {
            failureReason = $"RenameTarget property type '{property.PropertyType.FullName ?? property.PropertyType.Name}' does not accept '{target.GetType().FullName ?? target.GetType().Name}'";
            return false;
        }

        try
        {
            property.SetValue(provider, target);
            return true;
        }
        catch (TargetInvocationException ex) when (ex.InnerException != null)
        {
            failureReason = $"RenameTarget setter threw {ex.InnerException.GetType().Name}: {ex.InnerException.Message}";
            return false;
        }
        catch (Exception ex)
        {
            failureReason = $"RenameTarget setter failed: {ex.GetType().Name}: {ex.Message}";
            return false;
        }
    }
}
