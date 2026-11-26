export default function AppImage({
  src,
  alt = "",
  width,
  height,
  fill = false,
  priority = false,
  sizes,
  className = "",
  style,
  ...rest
}) {
  const resolvedStyle = fill
    ? {
        position: "absolute",
        inset: 0,
        width: "100%",
        height: "100%",
        ...style,
      }
    : style;

  return (
    <img
      src={src}
      alt={alt}
      width={fill ? undefined : width}
      height={fill ? undefined : height}
      sizes={sizes}
      loading={priority ? "eager" : undefined}
      fetchPriority={priority ? "high" : undefined}
      decoding={priority ? "sync" : "async"}
      className={className}
      style={resolvedStyle}
      {...rest}
    />
  );
}
